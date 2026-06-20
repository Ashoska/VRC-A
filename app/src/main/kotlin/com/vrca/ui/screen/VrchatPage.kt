package com.vrca.ui.screen

import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Public
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Brush
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
import com.vrca.vrchat.InAppAlertEvent
import com.vrca.vrchat.InstanceHistoryStore
import com.vrca.vrchat.InAppAlertGroup
import com.vrca.vrchat.InAppAlertState
import com.vrca.vrchat.NotificationActionReceiver
import com.vrca.vrchat.VrchatAuthManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
        // Cancel renders inside the login screen itself (the old top-bar Row
        // placement clashed with the app bar).
        com.vrca.vrchat.VrchatLoginScreen(
            pendingBanId = null,
            onCancel = { showLogin = false }
        ) { _, _ ->
            showLogin = false
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
        var showInstanceHistory by remember { mutableStateOf(false) }
        val repoHdr = vm.userPreferencesRepository
        val discordSeededHdr by repoHdr.discordSessionSeeded.collectAsState(initial = false)
        val discordStatusHdr by DiscordRpcState.statusFlow.collectAsState()

        // VRChat+ banner (profilePicOverride) as the card BACKGROUND — the round
        // userIcon stays the avatar. No banner (no VRChat+) → plain card.
        val bannerUrl = if (isLinked) p?.bannerUrl.orEmpty() else ""
        val hasBanner = bannerUrl.isNotBlank()
        ElevatedCard {
            Box {
                if (hasBanner) {
                    coil.compose.AsyncImage(
                        model = bannerUrl,
                        imageLoader = com.vrca.admin.VrchatImageLoader.get(ctx),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.matchParentSize()
                    )
                    // Scrim so name/status/chips/buttons stay readable over any banner.
                    Box(
                        Modifier
                            .matchParentSize()
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.45f),
                                        Color.Black.copy(alpha = 0.72f)
                                    )
                                )
                            )
                    )
                }
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
                        // Small trailing actions — over a banner they get a dark
                        // circle backing so they never blend into the image.
                        val actionBg = Modifier.size(36.dp).let {
                            if (hasBanner) it.background(Color.Black.copy(alpha = 0.40f), CircleShape) else it
                        }
                        if (p?.userId?.isNotBlank() == true) {
                            IconButton(onClick = {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW,
                                        Uri.parse("https://vrchat.com/home/user/${p.userId}"))
                                )
                            }, modifier = actionBg) {
                                Icon(
                                    Icons.Filled.OpenInNew,
                                    contentDescription = "View VRChat profile",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        IconButton(onClick = { showLogoutDialog = true }, modifier = actionBg) {
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
                        trustBadge(p?.trustRank.orEmpty())?.let { tb ->
                            Badge(containerColor = tb.color.copy(alpha = 0.22f)) {
                                Text(
                                    tb.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = tb.color
                                )
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

                    // Friends online (free, from the local friends cache) sits on the
                    // SAME row as the 24h Instance History re-invite picker chip.
                    // This sits ABOVE the current-world/player-count line so the
                    // social summary reads first when the user is in VRChat.
                    Spacer(Modifier.height(2.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        friendsOnline?.let { (online, total) ->
                            Icon(
                                Icons.Filled.Group,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "$online of $total friends online",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Surface(
                            onClick = { showInstanceHistory = true },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    Icons.Filled.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Instance History",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
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
        }

        // 24h instance-history picker. Entries (with join/left times) are read fresh
        // each open; the dialog then fetches each instance's live image/count/status.
        if (showInstanceHistory) {
            val historyTargets = remember {
                InstanceHistoryStore.list(ctx).map { e ->
                    val joined = clockTime(e.joinedMs)
                    val time = if (e.leftMs == 0L) "Joined $joined · Still here"
                        else "Joined $joined · Left ${clockTime(e.leftMs)}"
                    InstanceTarget(
                        location = e.location,
                        label = e.worldName.ifBlank { "Instance" },
                        timeLabel = time
                    )
                }
            }
            InstanceListDialog(
                title = "Instance History (24h)",
                targets = historyTargets,
                onDismiss = { showInstanceHistory = false }
            )
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

/**
 * Process-level expand state for the "Notifications (N)" alert section.
 * Default EXPANDED; the user's choice persists across tab switches for the
 * life of the process and resets to expanded only on a real reopen (a fresh
 * process with the user looking at it). A headless OEM revival restarts the
 * process with no UI on screen, so by the time the user actually opens the
 * app again, resetting to the expanded default is exactly the intended
 * "reopen" behavior — no extra persistence needed.
 */
internal object AlertSectionState {
    val expanded = mutableStateOf(true)
}

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

/**
 * Trust rank chip: VRChat's tag names are OFFSET from the displayed rank names
 * (same mapping VRCX uses) — system_trust_known displays as "User",
 * system_trust_trusted as "Known User", system_trust_veteran as "Trusted User",
 * system_trust_legend as the hidden "Veteran". Colors follow VRChat's rank
 * colors (New User blue, User green, Known User orange, Trusted User purple,
 * Veteran yellow; Visitor grey). "Legendary" is deliberately excluded — it
 * does not exist.
 */
private data class TrustBadge(val label: String, val color: Color)

private fun trustBadge(rank: String): TrustBadge? = when (rank) {
    "system_trust_legend"  -> TrustBadge("Veteran", Color(0xFFFFD000))
    "system_trust_veteran" -> TrustBadge("Trusted User", Color(0xFFB18FE4))
    "system_trust_trusted" -> TrustBadge("Known User", Color(0xFFFF7B42))
    "system_trust_known"   -> TrustBadge("User", Color(0xFF2BCF5C))
    "system_trust_basic"   -> TrustBadge("New User", Color(0xFF1778FF))
    "" -> null
    else -> TrustBadge("Visitor", Color(0xFFCCCCCC))
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
private fun isGroupAlert(groupId: String): Boolean =
    groupId.startsWith("announcement_") || groupId.startsWith("event_") ||
        groupId.startsWith("group")

private fun alertMatchesFilter(groupId: String, filter: String): Boolean = when (filter) {
    "Groups" -> isGroupAlert(groupId)
    "Bio" -> groupId.startsWith("bio_")
    "Friends" -> !isGroupAlert(groupId) && !groupId.startsWith("bio_")
    else -> true
}

@Composable
private fun InAppAlertCards() {
    val ctx = LocalContext.current
    val groups by InAppAlertState.groups.collectAsState()
    if (groups.isEmpty()) return

    // Shared, process-scoped: expanded by default, survives tab switches,
    // resets only on a genuine app reopen (see AlertSectionState).
    var sectionExpanded by AlertSectionState.expanded
    var filter by rememberSaveable { mutableStateOf("All") }
    var showDismissAllConfirm by remember { mutableStateOf(false) }

    // Drive the relative timestamps ("just now" / "5m ago") so they advance on
    // their own — without a ticker Compose computes formatRelativeTime once and
    // never re-runs it, so the times only refreshed when a card was expanded/
    // collapsed or the tab was switched (i.e. on an unrelated recomposition).
    // Ticks every 1s so the seconds range counts up smoothly; only the cheap
    // formatRelativeTime Text nodes recompose (bio diffs are remembered), and the
    // loop cancels when the tab leaves composition.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    // "Are you sure?" before nuking every alert.
    if (showDismissAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDismissAllConfirm = false },
            title = { Text("Dismiss all notifications?") },
            text = { Text("This clears every in-app alert. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showDismissAllConfirm = false
                    val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                    // Clear in-app groups AND cancel each linked Android notification.
                    InAppAlertState.dismissAll(ctx).forEach { nm.cancel(it.hashCode()) }
                }) { Text("Dismiss all") }
            },
            dismissButton = {
                TextButton(onClick = { showDismissAllConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Notifications (${groups.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { sectionExpanded = !sectionExpanded }
                )
                // Dismiss-all sits to the LEFT of the collapse chevron, on the
                // same header row. Its own tap is consumed here, so it never
                // toggles the section.
                Surface(
                    onClick = { showDismissAllConfirm = true },
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    modifier = Modifier.height(30.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            Icons.Filled.ClearAll,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Text(
                            "Dismiss all",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (sectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (sectionExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clickable { sectionExpanded = !sectionExpanded }
                        .size(20.dp)
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
                                onClick = { filter = f },
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
                    } else {
                        // Lazy-render so an unbounded ("infinite") notification history
                        // only composes the cards actually on screen — no lag. Bounded
                        // height makes it its own scroll region inside the page's outer
                        // scroll (wraps to content when there are only a few).
                        val maxH = (LocalConfiguration.current.screenHeightDp * 0.7f).dp
                        LazyColumn(
                            modifier = Modifier.heightIn(max = maxH),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Key by group identity so each card's expanded state stays
                            // with ITS group even as new alerts prepend to the list.
                            items(filtered, key = { it.groupId }) { group ->
                                AlertGroupCard(group = group, nowMs = nowMs, onDismiss = {
                                    InAppAlertState.dismiss(ctx, group.groupId)
                                    val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                                    nm.cancel(group.groupId.hashCode())
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val delta = nowMs - timestampMs
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
private fun AlertGroupCard(group: InAppAlertGroup, nowMs: Long, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val eventCount = group.events.size
    val displayTitle = if (eventCount > 1) "${group.title} ($eventCount)" else group.title

    val latest = group.events.lastOrNull()
    val previewText = latest?.body?.takeIf { it.isNotBlank() }
        ?: latest?.eventTitle?.takeIf { it.isNotBlank() }
        ?: ""

    val scope = rememberCoroutineScope()
    var actionSending by remember { mutableStateOf(false) }
    var showInstancePicker by remember { mutableStateOf(false) }

    // Group-level actions, surfaced as compact header symbols (next to collapse +
    // dismiss). Invite-me targets are the DISTINCT instances this person invited you
    // to (repeat invites to the same instance already deduped); >1 opens the instance
    // picker, exactly 1 fires an instant self-invite. Open buttons are deduped to one
    // shared url (friend request + new friend, bio/name/rank history all point at the
    // same profile); groups with DISTINCT per-event urls (group posts) keep per-event
    // opens in the body instead.
    val inviteMeTargets = group.events
        .filter { it.actionType == NotificationActionReceiver.ACTION_INVITE_ME && !it.actionData.isNullOrBlank() }
        .map { InstanceTarget(it.actionData!!, it.eventTitle ?: it.body) }
        .distinctBy { it.location }
    val inviteUserData = group.events
        .firstOrNull { it.actionType == NotificationActionReceiver.ACTION_INVITE_USER && !it.actionData.isNullOrBlank() }
        ?.actionData
    val sharedOpenUrl = group.events.mapNotNull { it.url }.distinct().singleOrNull()
    val showPerEventOpen = sharedOpenUrl == null
    val headerOpenUrl = sharedOpenUrl
        ?: group.url?.takeIf { group.events.none { e -> e.url != null } }

    fun doInstantAction(actionType: String, data: String) {
        actionSending = true
        scope.launch {
            val r = NotificationActionReceiver.perform(ctx, actionType, data)
            Toast.makeText(ctx, NotificationActionReceiver.feedback(actionType, r), Toast.LENGTH_LONG).show()
            actionSending = false
        }
    }

    if (showInstancePicker) {
        InstanceListDialog(
            title = group.title,
            targets = inviteMeTargets,
            onDismiss = { showInstancePicker = false }
        )
    }

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
                // Title block is the expand tap target.
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded }
                ) {
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
                            formatRelativeTime(it.timestampMs, nowMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
                // Action symbols (invite / open) sit to the LEFT of the collapse
                // chevron. Each gets a clearly-distinct color so they don't blur
                // together: invite = secondary, open = tertiary, collapse = primary
                // tint, dismiss = error tint.
                if (inviteMeTargets.isNotEmpty() || inviteUserData != null) {
                    Spacer(Modifier.width(6.dp))
                    val isInviteMe = inviteMeTargets.isNotEmpty()
                    HeaderActionSymbol(
                        icon = if (isInviteMe) Icons.AutoMirrored.Filled.Login else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isInviteMe) "Invite me to that instance" else "Invite them to your instance",
                        container = MaterialTheme.colorScheme.secondaryContainer,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        loading = actionSending
                    ) {
                        when {
                            inviteMeTargets.size > 1 -> showInstancePicker = true
                            inviteMeTargets.size == 1 ->
                                doInstantAction(NotificationActionReceiver.ACTION_INVITE_ME, inviteMeTargets[0].location)
                            inviteUserData != null ->
                                doInstantAction(NotificationActionReceiver.ACTION_INVITE_USER, inviteUserData)
                        }
                    }
                }
                if (headerOpenUrl != null) {
                    Spacer(Modifier.width(6.dp))
                    HeaderActionSymbol(
                        icon = Icons.Filled.OpenInNew,
                        contentDescription = "Open in VRChat",
                        container = MaterialTheme.colorScheme.tertiaryContainer,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(headerOpenUrl)))
                    }
                }
                // Collapse chevron — to the RIGHT of the action symbols.
                Spacer(Modifier.width(6.dp))
                Surface(
                    onClick = { expanded = !expanded },
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
                // Dismiss.
                Spacer(Modifier.width(6.dp))
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
                    // Body shows only per-event "Open in VRChat" for groups whose
                    // events carry DISTINCT urls (e.g. separate group posts/events), so
                    // each can be opened individually. Shared opens and all invite
                    // actions live as compact symbols in the header instead.
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
                                    if (showPerEventOpen && event.url != null) {
                                        Spacer(Modifier.height(10.dp))
                                        CompactAlertButton(
                                            label = "Open in VRChat",
                                            icon = Icons.Filled.OpenInNew,
                                            prominent = false,
                                            enabled = true,
                                            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url!!))) }
                                        )
                                    }
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
                                        formatRelativeTime(event.timestampMs, nowMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    // Per-event open only for distinct-url groups
                                    // (group posts/events); shared opens are in the header.
                                    if (showPerEventOpen && event.url != null) {
                                        Spacer(Modifier.height(8.dp))
                                        CompactAlertButton(
                                            label = "Open in VRChat",
                                            icon = Icons.Filled.OpenInNew,
                                            prominent = false,
                                            enabled = true,
                                            onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url!!))) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactAlertButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    prominent: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        colors = if (prominent) ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        ) else ButtonDefaults.filledTonalButtonColors(),
        modifier = Modifier.fillMaxWidth().height(38.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Extracts the `wrld_xxx` world ID from a VRChat instance location string. */
private fun extractWorldId(location: String): String? =
    location.substringBefore(':').takeIf { it.startsWith("wrld_") }

/**
 * Parses the instance type from the location string segments.
 * VRChat locations: `wrld_xxx:id~type(params)~nonce(...)`.
 * Returns a pair of (typeLabel, navigableId) where navigableId is a group/user ID
 * that can be used for deep-linking, or null if not applicable.
 */
private data class InstanceTypeInfo(
    val label: String,
    val navigableUrl: String? = null
)

private fun parseInstanceType(
    location: String,
    apiType: String = "",
    apiOwnerId: String = "",
    apiGroupId: String = ""
): InstanceTypeInfo {
    val type = apiType.ifBlank {
        val afterColon = location.substringAfter(':', "")
        when {
            afterColon.contains("~friends(") -> "friends"
            afterColon.contains("~hidden(") -> "hidden"
            afterColon.contains("~group(") -> "group"
            afterColon.contains("~private(") && afterColon.contains("~canRequestInvite") -> "invite+"
            afterColon.contains("~private(") -> "invite"
            afterColon.isNotBlank() && !afterColon.contains('~') -> "public"
            else -> ""
        }
    }
    val groupId = apiGroupId.ifBlank {
        val m = Regex("~group\\((grp_[^)]+)\\)").find(location)
        m?.groupValues?.getOrNull(1) ?: ""
    }
    val ownerId = apiOwnerId.ifBlank {
        val m = Regex("~(?:friends|hidden)\\((usr_[^)]+)\\)").find(location)
        m?.groupValues?.getOrNull(1) ?: ""
    }
    return when (type) {
        "public" -> InstanceTypeInfo("Public")
        "friends" -> InstanceTypeInfo(
            "Friends",
            if (ownerId.startsWith("usr_")) "https://vrchat.com/home/user/$ownerId" else null
        )
        "hidden" -> InstanceTypeInfo(
            "Friends+",
            if (ownerId.startsWith("usr_")) "https://vrchat.com/home/user/$ownerId" else null
        )
        "group" -> InstanceTypeInfo(
            "Group",
            if (groupId.startsWith("grp_")) "https://vrchat.com/home/group/$groupId" else null
        )
        "invite" -> InstanceTypeInfo("Invite")
        "invite+" -> InstanceTypeInfo("Invite+")
        else -> InstanceTypeInfo("")
    }
}

/** A target instance for the invite/history picker. [timeLabel] (history only) shows
 *  the join/left times so users can cross-reference what they played and when. */
private data class InstanceTarget(
    val location: String,
    val label: String,
    val timeLabel: String? = null
)

private fun clockTime(ms: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(ms))

/**
 * A compact, app-styled picker that lists instances (world image, live occupancy, an
 * open/dead/inaccessible status, and a per-instance Invite button). Used by world-
 * invite cards (when one person sent several instances) and the 24h Instance History.
 * Instance info is fetched fresh from the user's own VRChat session every time the
 * dialog opens, so counts and closed/dead status are never stale.
 */
@Composable
private fun InstanceListDialog(
    title: String,
    targets: List<InstanceTarget>,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    // Resolved instance info, filled in INCREMENTALLY (newest target first) so the
    // list renders immediately and each row fills in as its fetch lands — the user
    // is never stuck on a full-screen spinner waiting for every instance.
    var infos by remember { mutableStateOf<Map<String, VrchatAuthManager.InstanceInfo>>(emptyMap()) }
    LaunchedEffect(Unit) {
        // targets are already ordered current → most-recently-left (newest to oldest),
        // so resolving them in order fills the list top-down.
        for (t in targets) {
            val info = VrchatAuthManager.fetchInstanceInfo(ctx, t.location)
            infos = infos + (t.location to info)
        }
    }
    val resolving = infos.size < targets.size
    // usePlatformDefaultWidth = false lets the card span nearly the full screen
    // width (the default narrow dialog width was clipping world names + the
    // join/left time line). Padding keeps small margins on each side.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 620.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                // Header: world icon + title, dismiss on the right.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Public,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        onClick = onDismiss,
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // Subtle progress hint while later (older) instances are still
                // resolving — the rows above it are already interactive.
                if (resolving && targets.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Checking instances... (${infos.size}/${targets.size})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                if (targets.isEmpty()) {
                    Text(
                        "Nothing to show yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    // Render every row immediately; each one shows its own "Checking"
                    // placeholder until its info arrives (newest first).
                    Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (t in targets) InstanceRow(t, infos[t.location])
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceRow(target: InstanceTarget, info: VrchatAuthManager.InstanceInfo?) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    val status = info?.status ?: VrchatAuthManager.InstanceStatus.UNKNOWN
    val isOpen = status == VrchatAuthManager.InstanceStatus.OPEN
    val canJoin = isOpen && (info?.players ?: 0) > 0
    val worldId = extractWorldId(target.location)
    val typeInfo = parseInstanceType(
        target.location,
        info?.instanceType ?: "",
        info?.ownerId ?: "",
        info?.groupId ?: ""
    )
    val typeColor = when (typeInfo.label) {
        "Public" -> Color(0xFF4CAF50)
        "Friends", "Friends+" -> Color(0xFF42A5F5)
        "Group" -> Color(0xFFAB47BC)
        "Invite", "Invite+" -> Color(0xFFFFB300)
        else -> MaterialTheme.colorScheme.outline
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        // Outer column: the main row (image · name/meta · invite) on top, then the
        // join/left time on its OWN full-width line below. Times have a predictable
        // max length ("Joined 12:00 AM · Left 12:00 AM") so giving them the whole
        // card width means they never clip; the world name is free to wrap to a
        // second line up top without fighting the time for horizontal space.
        Column(Modifier.padding(8.dp)) {
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Type-colored accent bar (echoes the notification cards) — instant
                // visual cue for the instance type.
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(typeColor.copy(alpha = 0.85f))
                )
                Spacer(Modifier.width(8.dp))
                val img = info?.worldImageUrl.orEmpty()
                if (img.isNotBlank()) {
                    coil.compose.AsyncImage(
                        model = img,
                        imageLoader = com.vrca.admin.VrchatImageLoader.get(ctx),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 64.dp, height = 48.dp)
                            .clip(MaterialTheme.shapes.medium)
                    )
                } else {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(width = 64.dp, height = 48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.Public, null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val worldName = info?.worldName?.takeIf { it.isNotBlank() }
                        ?: target.label.ifBlank { "Instance" }
                    // Name may wrap to TWO lines for long world names (tap to open
                    // the world page); the metadata sits below it.
                    Text(
                        worldName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (worldId != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (worldId != null) Modifier.clickable {
                            ctx.startActivity(
                                Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://vrchat.com/home/world/$worldId"))
                            )
                        } else Modifier
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (typeInfo.label.isNotBlank()) {
                            val chipModifier = if (typeInfo.navigableUrl != null)
                                Modifier
                                    .clip(MaterialTheme.shapes.extraSmall)
                                    .clickable {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(typeInfo.navigableUrl))
                                        )
                                    }
                            else Modifier.clip(MaterialTheme.shapes.extraSmall)
                            Box(
                                chipModifier
                                    .background(typeColor.copy(alpha = 0.18f))
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    typeInfo.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = typeColor,
                                    maxLines = 1
                                )
                            }
                        }
                        val (statusText, statusColor) = when {
                            isOpen && (info?.players ?: 0) == 0 ->
                                "Empty" to Color(0xFFEF5350)
                            isOpen ->
                                "${info?.players ?: 0}/${info?.capacity ?: 0} players" to Color(0xFF4CAF50)
                            status == VrchatAuthManager.InstanceStatus.CLOSED ->
                                "Closed" to Color(0xFFEF5350)
                            status == VrchatAuthManager.InstanceStatus.INACCESSIBLE ->
                                "Locked" to Color(0xFFFFB300)
                            else ->
                                "Checking" to MaterialTheme.colorScheme.outline
                        }
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                // Icon-only invite affordance (matches the card's circular symbols).
                // Muted + disabled when the instance is dead/empty/inaccessible.
                val inviteEnabled = canJoin && !sending
                Surface(
                    onClick = {
                        sending = true
                        scope.launch {
                            val r = VrchatAuthManager.inviteSelfToInstance(ctx, target.location)
                            Toast.makeText(
                                ctx,
                                NotificationActionReceiver.feedback(
                                    NotificationActionReceiver.ACTION_INVITE_ME, r
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                            sending = false
                        }
                    },
                    enabled = inviteEnabled,
                    shape = CircleShape,
                    color = if (canJoin) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (sending) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Login,
                                contentDescription = "Invite me",
                                modifier = Modifier.size(19.dp),
                                tint = if (canJoin) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            // Join/left time on its own full-width line — predictable max length, so
            // with the whole card width it never clips even at "12:00 AM" extremes.
            if (!target.timeLabel.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        modifier = Modifier.size(13.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        target.timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Compact circular icon button matching the card's collapse/dismiss affordances. */
@Composable
private fun HeaderActionSymbol(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    container: Color,
    tint: Color,
    loading: Boolean = false,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = !loading,
        shape = CircleShape,
        color = container,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
            } else {
                Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}
