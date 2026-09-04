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
import androidx.compose.foundation.border
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
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Public
import androidx.compose.foundation.layout.aspectRatio
import com.vrca.vrchat.AlertImageStore
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.filled.ClearAll
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.discord.DiscordRpcState
import com.vrca.discord.DiscordRpcStatus
import com.vrca.ui.common.VrcaConfirmDialog
import com.vrca.ui.common.VrcaDialogCopy
import com.vrca.ui.viewmodel.VrcaViewModel
import com.vrca.vrchat.InAppAlertEvent
import com.vrca.vrchat.EventSeriesStore
import com.vrca.vrchat.InstanceHistoryStore
import com.vrca.vrchat.InAppAlertGroup
import com.vrca.vrchat.InAppAlertState
import com.vrca.vrchat.NotificationActionReceiver
import com.vrca.vrchat.VrchatAuthManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.vrca.vrchat.VrchatPipelineService
import com.vrca.vrchat.VrchatPipelineState

@OptIn(ExperimentalFoundationApi::class)
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

    // ----- In-app alert state, hoisted so the "Notifications (N)" header can be
    // a real LazyColumn stickyHeader (pins to the top of the whole tab while the
    // cards scroll underneath it, instead of clipping behind the app bar). -----
    val alertGroups by InAppAlertState.groups.collectAsState()
    var sectionExpanded by AlertSectionState.expanded
    var filter by rememberSaveable { mutableStateOf("All") }
    var showDismissAllConfirm by remember { mutableStateOf(false) }
    // Drives the relative timestamps ("just now" / "5m ago") so they advance on
    // their own; only the cheap formatRelativeTime Text nodes recompose.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    // Live refresh for on-screen event/announcement alerts: while this tab is
    // composed, periodically re-sweep the groups behind them so the interested
    // count, an edited start time/description, and a late banner UPDATE IN PLACE
    // — the card no longer freezes at fire-time data. First pass runs shortly
    // after entering the tab (stale alerts from a previous session catch up),
    // then every 60s; the enricher is debounced per group and skips groups whose
    // alerts were dismissed. Only rich event/announcement groups are swept —
    // bounded to a handful of groups → ~2 REST calls each per cycle, on-tab only.
    LaunchedEffect(Unit) {
        // Sweep away events that have ENDED so concluded events don't linger in
        // "Going" / the notifications area (timestamp-only, no network). Runs on
        // entry (immediate declutter) and every cycle.
        InAppAlertState.pruneEndedEvents(ctx, System.currentTimeMillis())
        delay(5_000)
        while (true) {
            InAppAlertState.pruneEndedEvents(ctx, System.currentTimeMillis())
            val groupIds = InAppAlertState.groups.value
                .asSequence()
                .filter { it.groupId.startsWith("event_grp_") || it.groupId.startsWith("announcement_grp_") }
                .flatMap { g -> g.events.asSequence().mapNotNull { it.groupRefId } }
                .distinct()
                .take(6)
                .toList()
            for (gid in groupIds) {
                runCatching { com.vrca.vrchat.GroupAlertEnricher.enrich(ctx, gid, minIntervalMs = 55_000) }
            }
            delay(60_000)
        }
    }

    var showInstanceHistory by remember { mutableStateOf(false) }

    // Whole-tab scroller. Was a verticalScroll Column (PageContainer); a
    // LazyColumn is required so the notifications header can be a stickyHeader.
    // Matches PageContainer's padding / spacing / tap-to-clear-focus behavior.
    val focusManager = LocalFocusManager.current
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      // Confirmed-dead VRChat session: OSC is gated — explain + one-tap sign-in.
      if (vm.vrchatAuthDead) {
          item {
              com.vrca.ui.common.VrchatSessionExpiredBanner(onSignIn = { showLogin = true })
          }
      }
      item {
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
                    // No extra Spacer here — the parent Column's 10dp spacedBy keeps
                    // this row (friends text + Instance History button) on the same
                    // uniform rhythm as the chips row above; a stray 2dp spacer made
                    // this one gap 12dp and read as a "weird" uneven gap.
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
      } // end identity item

      // Avatar tools (TEMPORARY home on the VRChat tab): avtrdb search + OSC
      // avatar-size control. Headset shows the size slider (OSC-out is loopback).
      item { AvatarToolsCard(vm) }

      item { VrchatStatusBanner() }

      // In-app alerts: a real stickyHeader so the "Notifications (N)" header,
      // Dismiss-all button, and filter chips PIN to the top of the tab while the
      // cards scroll underneath them (they used to clip behind the app bar — the
      // old nested LazyColumn only pinned them relative to the inner card list).
      // Discord RPC management + the duplicated About/trust card are GONE from
      // this tab (docs/ui-revamp.md): the full Discord setup lives in
      // Settings → Accounts; the identity header above keeps the small RPC chip.
      inAppAlertSection(
          ctx = ctx,
          groups = alertGroups,
          sectionExpanded = sectionExpanded,
          onToggleExpanded = { sectionExpanded = !sectionExpanded },
          filter = filter,
          onFilterChange = { filter = it },
          onDismissAll = { showDismissAllConfirm = true },
          nowMs = nowMs
      )
    }

    // 24h instance-history picker. Entries (with join/left times) are read fresh
    // each open; the dialog then fetches each instance's live image/count/status.
    if (showInstanceHistory) {
        // "Still here" is derived from the LIVE presence (the same signal the Discord
        // RPC reads): an open session counts as current only while the user's presence
        // location still equals this instance. The moment presence leaves the world
        // (offline / traveling / hopped), the label drops "Still here" — no reliance on
        // a stored flag firing. Keyed on the live location so it recomputes reactively.
        val liveLocation = presence?.location.orEmpty()
        val historyTargets = remember(liveLocation) {
            InstanceHistoryStore.list(ctx).map { e ->
                val isCurrent = liveLocation.isNotBlank() && liveLocation == e.location
                // One line per visit, newest first.
                val lines = e.sessions.asReversed().mapIndexed { idx, s ->
                    val joined = clockTime(s.joinedMs)
                    when {
                        s.leftMs != 0L -> "Joined $joined · Left ${clockTime(s.leftMs)}"
                        // Newest open session AND presence is still in this instance.
                        idx == 0 && isCurrent -> "Joined $joined · Still here"
                        else -> "Joined $joined"
                    }
                }
                InstanceTarget(
                    location = e.location,
                    label = e.worldName.ifBlank { "Instance" },
                    timeLines = lines,
                    // Stored world thumb + type (pre-loaded on instance entry, persisted
                    // via AlertImageStore for the entry's 24h lifetime) render instantly
                    // while live info loads — so opening history cold never looks bad.
                    imageUrl = e.imageUrl,
                    instanceType = e.instanceType
                )
            }
        }
        InstanceListDialog(
            title = "Instance History (24h)",
            targets = historyTargets,
            onDismiss = { showInstanceHistory = false },
            liveRefresh = false // history: one-time grab, no 15s re-fetch loop
        )
    }

    // "Are you sure?" before nuking every alert (moved out of the old
    // InAppAlertCards composable now that the section is a LazyListScope helper).
    if (showDismissAllConfirm) {
        VrcaConfirmDialog(
            title = "Dismiss all notifications?",
            body = "This clears every in-app alert. This can't be undone.",
            confirmLabel = "Dismiss all",
            destructive = true,
            onConfirm = {
                showDismissAllConfirm = false
                val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                // Clear in-app groups AND cancel each linked Android notification.
                InAppAlertState.dismissAll(ctx).forEach { nm.cancel(it.hashCode()) }
            },
            onDismiss = { showDismissAllConfirm = false }
        )
    }

    // Sign out confirmation — shared canonical copy (matches Settings).
    if (showLogoutDialog) {
        VrcaConfirmDialog(
            title = VrcaDialogCopy.VRC_SIGN_OUT_TITLE,
            body = VrcaDialogCopy.VRC_SIGN_OUT_BODY,
            confirmLabel = "Sign out",
            destructive = true,
            onConfirm = {
                showLogoutDialog = false
                // logout() emits loggedOutSignal → vm.vrchatLoggedOut flips
                // (isLinked above is derived from it) and the OSC gate blocks.
                VrchatAuthManager.logout(ctx)
                // Stop pipeline service
                ctx.stopService(Intent(ctx, VrchatPipelineService::class.java))
            },
            onDismiss = { showLogoutDialog = false }
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

/** Collapse state for the "📌 Pinned" sub-section — same process-scoped model as
 *  [AlertSectionState]: it survives tab switches but resets to expanded on a cold
 *  process start (a headless revival has no UI, so the reset is only ever seen on
 *  a genuine reopen — the intended behavior). */
internal object PinnedSectionState {
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

/**
 * In-app alerts rendered straight into the VRChat-tab LazyColumn (NOT its own
 * scroll region) so the header can be a real `stickyHeader`. The header row
 * (Notifications (N) + Dismiss all + chevron) and the filter chips live inside
 * the sticky header on an OPAQUE background, so they pin to the top of the tab
 * and the cards scroll cleanly underneath — fixing the old behavior where the
 * header clipped behind the app bar (the previous nested LazyColumn only pinned
 * them relative to the inner card list, not the whole page).
 *
 * State (expanded/filter/nowMs/dismiss-all) is hoisted into VrchatStatusPage.
 */
/** Temporary Avatar tools card on the VRChat tab: avatar-database search
 *  (avtrdb) + avatar-size control (OSC /avatar/eyeheight). */
@Composable
private fun AvatarToolsCard(vm: VrcaViewModel) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<com.vrca.vrchat.AvatarSearch.Result>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(12) }
    var searchSeq by remember { mutableStateOf(0) }
    // Avatar ids confirmed dead (image 404 + existence check) — hidden from results.
    val deadIds = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    // Platform filter (null = all). Filters the SHOWN results by compatibility — CLIENT-side over the
    // whole grabbed match set, so toggling it never re-searches. `searchCapped` = the match set hit the
    // grab-all cap (very broad term), so there may be more beyond what was fetched.
    var platFilter by remember { mutableStateOf<String?>(null) }
    var searchCapped by remember { mutableStateOf(false) }

    // Paged (Google-style) search over the sharded catalog. `pageCache` holds each fetched
    // page's rows for this query; `pageTotal` is the exact candidate count (so Next is exact
    // and we always know when to stop). Page 0 fetches page 0 AND prefetches page 1 (the "40
    // on the first search"); each Next shows the prefetched page and prefetches the next.
    val paged = com.vrca.vrchat.AvatarSearch.pagedSearchLive()
    val pageSize = 20
    var pageIndex by remember { mutableStateOf(0) }
    val pageCache = remember { androidx.compose.runtime.mutableStateMapOf<Int, List<com.vrca.vrchat.AvatarSearch.Result>>() }
    var pageTotal by remember { mutableStateOf(0) }

    // INSTA-UPDATE a shown search row when the harvest finds its live metadata changed (name/author/
    // platform) — mirrors the dead-hiding, but for a changed avatar. Patches the flat list AND any
    // cached page holding it. Dispatched to the composable (main) scope for safe Compose state writes.
    fun refreshResult(u: com.vrca.vrchat.AvatarSearch.Result) {
        scope.launch {
            results = results.map { if (it.id == u.id) u else it }
            pageCache.keys.toList().forEach { p ->
                pageCache[p]?.let { list -> if (list.any { it.id == u.id }) pageCache[p] = list.map { if (it.id == u.id) u else it } }
            }
        }
    }

    // Silently absorb avatars from the external sources (avtrdb/mirrors) so the sharded
    // catalog fills over time — NOT shown inline (the paged view is our catalog only, to keep
    // pages stable). No UI gating; contribute-back happens in the background.
    fun backgroundContribute(seq: Int, q: String) {
        scope.launch {
            val remote = com.vrca.vrchat.AvatarSearch.remoteFill(ctx, q)
            if (seq != searchSeq) return@launch
            com.vrca.vrchat.AvatarGlobalDb.harvestSearchResults(ctx, remote, ::refreshResult)
        }
    }

    // Fetch one page into the cache (idempotent); returns its total so Next-enable is exact.
    suspend fun fetchPage(seq: Int, q: String, p: Int) {
        if (seq != searchSeq || pageCache.containsKey(p)) return
        val rp = com.vrca.vrchat.AvatarSearch.searchPage(ctx, q, p, pageSize)
        if (seq != searchSeq) return
        pageCache[p] = rp.results
        pageTotal = rp.total
    }

    fun runSearch() {
        if (query.isBlank()) return
        searched = true; shown = 12; deadIds.clear()
        searchSeq += 1
        val seq = searchSeq
        val q = query
        // New search ⇒ drop the previous query's shard/candidate caches from the app.
        com.vrca.vrchat.AvatarSearch.evictSearchCache()
        pageCache.clear(); pageIndex = 0; pageTotal = 0

        if (paged) {
            scope.launch {
                searching = true; results = emptyList()
                // Grab the WHOLE match set once (cheap — see searchShardedAll), so the platform filter
                // + "See more" run CLIENT-side and toggling a filter never re-searches.
                val all = com.vrca.vrchat.AvatarSearch.searchAllMatches(ctx, q)
                if (seq != searchSeq) return@launch
                results = all.results
                searchCapped = all.hasMore
                searching = false
                backgroundContribute(seq, q)               // absorb avtrdb extras + insta-update rows
            }
            return
        }

        scope.launch {
            // LEGACY (pre-cutover) path: LOCAL FIRST from the in-memory whole-map, then fold
            // in the external sources, single list with a "See more" button.
            val local = com.vrca.vrchat.AvatarSearch.localResults(q)
            if (seq != searchSeq) return@launch
            results = local
            val enough = local.size >= 12
            searching = local.isEmpty()
            loadingMore = local.isNotEmpty() && !enough
            val remote = com.vrca.vrchat.AvatarSearch.remoteFill(ctx, q)
            if (seq != searchSeq) return@launch
            val merged = LinkedHashMap<String, com.vrca.vrchat.AvatarSearch.Result>()
            for (r in local + remote) {
                val k = r.id.trim().lowercase()
                if (k.startsWith("avtr_")) merged.putIfAbsent(k, r)
            }
            results = merged.values.toList()
            searching = false; loadingMore = false
            com.vrca.vrchat.AvatarGlobalDb.harvestSearchResults(ctx, results, ::refreshResult)
        }
    }

    // Move to a page: show its cached rows instantly (fetch if missing), then prefetch the next.
    fun goToPage(p: Int) {
        if (p < 0) return
        val seq = searchSeq
        val q = query
        scope.launch {
            if (!pageCache.containsKey(p)) { searching = true; fetchPage(seq, q, p) }
            if (seq != searchSeq) return@launch
            pageIndex = p
            results = pageCache[p] ?: emptyList()
            searching = false
            fetchPage(seq, q, p + 1)                        // always keep one page ahead
        }
    }

    // Evict the sharded-search caches when the card leaves composition (tab-away / close).
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { com.vrca.vrchat.AvatarSearch.evictSearchCache() }
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Avatar tools", style = MaterialTheme.typography.titleMedium)

            // --- avatar search (avtrdb) ---
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Search avatars") },
                singleLine = true,
                trailingIcon = {
                    androidx.compose.material3.IconButton(onClick = { runSearch() }) {
                        Icon(Icons.Filled.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() })
            )
            // Platform filter — narrow the shown results by PC / Quest / iOS compatibility.
            if (searched) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = platFilter == null, onClick = { platFilter = null }, label = { Text("All") })
                    listOf("PC", "Quest", "iOS").forEach { plat ->
                        FilterChip(
                            selected = platFilter == plat,
                            onClick = { platFilter = if (platFilter == plat) null else plat },
                            label = { Text(plat) },
                            leadingIcon = { PlatformSymbol(plat) }
                        )
                    }
                }
            }
            when {
                searching || loadingMore ->
                    androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
                searched && results.isEmpty() -> Text(
                    "No results.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val visible = results.filter {
                it.id !in deadIds && (platFilter == null || it.platforms.any { p -> p.equals(platFilter, true) })
            }
            // Whole match set is already grabbed — filter + "See more" are CLIENT-side, so changing the
            // platform filter re-slices instantly with no re-search.
            visible.take(shown).forEach { r ->
                AvatarResultRow(ctx, r, onDead = { if (it !in deadIds) deadIds.add(it) })
            }
            if (visible.size > shown) {
                androidx.compose.material3.TextButton(
                    onClick = { shown += 12 },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("See more (${visible.size - shown} more)") }
            }
            // A no-results-after-filter hint (there ARE matches, just none for this platform).
            if (searched && !searching && results.isNotEmpty() && visible.isEmpty() && platFilter != null) {
                Text(
                    "No $platFilter avatars in these results — tap All to see the rest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (searched && searchCapped) {
                Text(
                    "Showing the first ${results.size} matches — narrow your search for more.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            androidx.compose.material3.Divider()

            // --- avatar size (OSC /avatar/eyeheight, live read via OSCQuery) ---
            Text("Avatar size (eye height)", style = MaterialTheme.typography.titleSmall)

            // Live IN-GAME height from VRChat (OSCQuery pull); the slider mirrors it.
            val liveHeight by com.vrca.osc.VrcaOscState.eyeHeightFlow.collectAsState()
            val defaultHeight = com.vrca.osc.VrcaOscState.defaultEyeHeightMeters

            var sliderVal by remember { mutableStateOf(liveHeight ?: 1.6f) }
            var dragging by remember { mutableStateOf(false) }
            // Sync the slider to the in-game height whenever we're not dragging it.
            androidx.compose.runtime.LaunchedEffect(liveHeight) {
                if (!dragging) liveHeight?.let { sliderVal = it.coerceIn(0.1f, 10f) }
            }

            Text(
                "In-game: " + (liveHeight?.let { "%.2f m".format(it) } ?: "—"),
                style = MaterialTheme.typography.bodyMedium
            )

            androidx.compose.material3.Slider(
                value = sliderVal.coerceIn(0.1f, 10f),
                onValueChange = { dragging = true; sliderVal = it },
                valueRange = 0.1f..10f,
                onValueChangeFinished = { dragging = false; vm.setAvatarEyeHeight(sliderVal) }
            )

            // Type ANY value (no app-imposed min/max — VRChat clamps to its own range).
            var heightText by remember { mutableStateOf("") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Height (m)") },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                    )
                )
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Button(
                    onClick = {
                        heightText.trim().toFloatOrNull()?.let {
                            vm.setAvatarEyeHeight(it); sliderVal = it.coerceIn(0.1f, 10f)
                        }
                    },
                    enabled = heightText.trim().toFloatOrNull() != null
                ) { Text("Set") }
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Default: " + (defaultHeight?.let { "%.2f m".format(it) } ?: "—"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(
                    onClick = { vm.resetAvatarHeightToDefault()?.let { sliderVal = it.coerceIn(0.1f, 10f) } },
                    enabled = defaultHeight != null
                ) { Text("Reset to default") }
            }
        }
    }
}

/** VRChat avatar performance rank badge (Excellent…Very Poor). Shows the rank for the user's own
 *  platform (Quest on the headset build, else PC), falling back to any known one. Hidden when unknown. */
@Composable
private fun PerfBadge(r: com.vrca.vrchat.AvatarSearch.Result) {
    val rank = when {
        com.vrca.BuildConfig.IS_HEADSET_BUILD && r.perfQuest < 5 -> r.perfQuest
        r.perfPc < 5 -> r.perfPc
        r.perfQuest < 5 -> r.perfQuest
        r.perfIos < 5 -> r.perfIos
        else -> return
    }
    val (label, color) = when (rank) {
        0 -> "Excellent" to Color(0xFF3CCF4E)
        1 -> "Good" to Color(0xFF8CC63F)
        2 -> "Medium" to Color(0xFFFFC107)
        3 -> "Poor" to Color(0xFFFF7B42)
        else -> "Very Poor" to Color(0xFFE53935)
    }
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp), color = color.copy(alpha = 0.18f)) {
        Text(
            label, style = MaterialTheme.typography.labelSmall, color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

@Composable
private fun AvatarResultRow(
    ctx: android.content.Context,
    r: com.vrca.vrchat.AvatarSearch.Result,
    onDead: (String) -> Unit = {}
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var cloning by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        coil.compose.AsyncImage(
            model = r.imageUrl,
            imageLoader = com.vrca.admin.VrchatImageLoader.get(ctx),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // A dead avatar's thumbnail 404s. On an image error, CONFIRM the avatar is
            // gone (avoids hiding a valid one on a network blip), then hide it + report
            // it dead so the admin bot removes it from the catalog.
            onError = {
                scope.launch {
                    if (com.vrca.vrchat.VrchatAuthManager.avatarExists(ctx, r.id) == false) {
                        onDead(r.id)
                        val fid = r.imageFileId
                        if (fid != null) com.vrca.vrchat.AvatarGlobalDb.report(ctx, fid, r.id, "dead")
                    }
                }
            },
            modifier = Modifier
                .size(width = 56.dp, height = 42.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        )
        Column(Modifier.weight(1f)) {
            Text(
                r.name.ifBlank { "(unnamed)" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                "by ${r.author.ifBlank { "unknown" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            // Platform SYMBOLS (same glyphs as the instance roster) + performance rank.
            if (r.platforms.isNotEmpty() || r.perfPc < 5 || r.perfQuest < 5 || r.perfIos < 5) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    r.platforms.forEach { PlatformSymbol(it) }
                    PerfBadge(r)
                }
            }
        }
        // Clone/wear this avatar directly (we have its id). Also contributes it to
        // the global catalog (fetching its file id if the search source lacked one).
        androidx.compose.material3.IconButton(
            enabled = !cloning,
            onClick = {
                if (cloning) return@IconButton
                cloning = true
                scope.launch {
                    val res = com.vrca.vrchat.VrchatAuthManager.selectAvatar(ctx, r.id)
                    // Contribute to our catalog: use the search file id if present,
                    // else resolve it once via GET /avatars/{id}.
                    val fid = r.imageFileId ?: com.vrca.vrchat.VrchatAuthManager.avatarCatalogEntry(ctx, r.id)?.fileId
                    if (fid != null) com.vrca.vrchat.AvatarGlobalDb.contribute(
                        ctx, fid, r.id, r.name, r.author, r.authorId, r.platforms
                    )
                    android.widget.Toast.makeText(
                        ctx,
                        if (res.ok) "Cloned ${r.name.ifBlank { "avatar" }} — shows on your next avatar reload"
                        else (res.error ?: "Couldn't wear this avatar"),
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    // If the clone failed because the avatar is gone, hide + report it.
                    if (!res.ok && com.vrca.vrchat.VrchatAuthManager.avatarExists(ctx, r.id) == false) {
                        onDead(r.id)
                        val df = r.imageFileId ?: fid
                        if (df != null) com.vrca.vrchat.AvatarGlobalDb.report(ctx, df, r.id, "dead")
                    }
                    cloning = false
                }
            }
        ) {
            if (cloning) {
                androidx.compose.material3.CircularProgressIndicator(
                    strokeWidth = 2.dp, modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(Icons.Filled.PeopleAlt, contentDescription = "Clone avatar",
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        androidx.compose.material3.IconButton(onClick = {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://vrchat.com/home/avatar/${r.id}")
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }) {
            Icon(Icons.Filled.OpenInNew, contentDescription = "Open in VRChat")
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.inAppAlertSection(
    ctx: android.content.Context,
    groups: List<InAppAlertGroup>,
    sectionExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    onDismissAll: () -> Unit,
    nowMs: Long
) {
    if (groups.isEmpty()) return

    stickyHeader(key = "alerts_header") {
        // Opaque background (the Scaffold's default container color) so the
        // cards disappear cleanly behind the pinned header instead of bleeding
        // through. Bottom padding extends the opaque band a touch below the
        // content for a clean edge against the scrolling cards.
        Surface(
            color = MaterialTheme.colorScheme.background,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(bottom = 4.dp)) {
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
                            .clickable { onToggleExpanded() }
                    )
                    // Dismiss-all sits to the LEFT of the collapse chevron, on the
                    // same header row. Its own tap is consumed here, so it never
                    // toggles the section.
                    Surface(
                        onClick = onDismissAll,
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
                            .clickable { onToggleExpanded() }
                            .size(20.dp)
                    )
                }
                // Filter chips pin WITH the header (they were inside the old
                // AnimatedVisibility body). Only shown while expanded.
                if (sectionExpanded) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "Friends", "Groups", "Bio").forEach { f ->
                            FilterChip(
                                selected = filter == f,
                                onClick = { onFilterChange(f) },
                                label = { Text(f, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (sectionExpanded) {
        val filtered = groups.filter { alertMatchesFilter(it.groupId, filter) }
        if (filtered.isEmpty()) {
            item(key = "alerts_empty") {
                Text(
                    "No ${filter.lowercase()} alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        } else {
            // THREE zones. Splitting is at the EVENT level, not the group: a group
            // can have SOME events the user signed up for and some they didn't, so
            // each FOLLOWED event becomes its OWN card in "Signed up Events" (real
            // groupId kept for its actions; unique display key), while the remaining
            // (unfollowed) events stay grouped together in Pinned (if the group is
            // manually pinned) or Other notifications. Un-following an event drops it
            // back into the remainder group automatically.
            val gold = Color(0xFFFFC64B)
            val silver = Color(0xFFC7CDD9)
            val signedUpCards = ArrayList<Pair<String, InAppAlertGroup>>()
            val pinnedCards = ArrayList<Pair<String, InAppAlertGroup>>()
            val removedCards = ArrayList<Pair<String, InAppAlertGroup>>()
            val restCards = ArrayList<Pair<String, InAppAlertGroup>>()
            // Series key for splitting: the stable seriesId (so two distinct series
            // never share a "Going"/"Removed" card), title fallback, then id.
            fun seriesKey(ev: InAppAlertEvent): String = when {
                !ev.seriesId.isNullOrBlank() -> "s:${ev.seriesId}"
                !ev.eventTitle.isNullOrBlank() ->
                    "t:${ev.eventTitle.trim().lowercase().replace(Regex("\\s+"), " ")}"
                else -> "id:${ev.id}"
            }
            for (g in filtered) {
                // Hide ENDED events at DISPLAY time (event groups only) so concluded
                // events never even flash before the async prune removes them from
                // storage. `removed` (red) cards are kept; non-event alerts untouched.
                val isEventGroup = g.groupId.startsWith("event_")
                val visible = if (isEventGroup)
                    g.events.filter { it.removed || !InAppAlertState.eventEnded(it, nowMs) }
                else g.events
                if (visible.isEmpty()) continue
                // A DELETED event leaves "Going" and becomes its own red "Removed"
                // card in the normal area; it's never signed-up or grouped-in.
                // "Going" for a RECURRING series is the aggregate: subscribed to the
                // representative OR any occurrence (from EventSeriesStore, updated by
                // the per-occurrence Repeats dialog). Non-recurring keeps the flag.
                fun isGoing(ev: InAppAlertEvent): Boolean {
                    if (ev.following == true) return true
                    return ev.recurring && !ev.seriesId.isNullOrBlank() && !ev.groupRefId.isNullOrBlank() &&
                        EventSeriesStore.anySubscribed(ctx, ev.groupRefId!!, ev.seriesId!!)
                }
                val followed = visible.filter { isGoing(it) && !it.removed }
                val removedEvts = visible.filter { it.removed }
                val others = visible.filter { !isGoing(it) && !it.removed }
                // Group the followed events by SERIES so a recurring event the user
                // signed up for shows as ONE card — its ~50 occurrences, each
                // independently marked following, would otherwise each spawn its own
                // "Going" card. The card's own recurring-collapse then picks the
                // nearest upcoming occurrence and shows the count.
                val bySeries = LinkedHashMap<String, MutableList<InAppAlertEvent>>()
                for (ev in followed) bySeries.getOrPut(seriesKey(ev)) { mutableListOf() }.add(ev)
                for ((skey, evs) in bySeries) {
                    signedUpCards.add("su_${g.groupId}_$skey" to g.copy(events = evs))
                }
                // Removed events: each series its own standalone red card.
                val byRemoved = LinkedHashMap<String, MutableList<InAppAlertEvent>>()
                for (ev in removedEvts) byRemoved.getOrPut(seriesKey(ev)) { mutableListOf() }.add(ev)
                for ((skey, evs) in byRemoved) {
                    removedCards.add("rm_${g.groupId}_$skey" to g.copy(events = evs))
                }
                if (others.isNotEmpty()) {
                    val rem = g.copy(events = others)
                    if (g.pinned) pinnedCards.add("pin_${g.groupId}" to rem)
                    else restCards.add(g.groupId to rem)
                }
            }
            val togglePin: (InAppAlertGroup) -> Unit = { g ->
                val ok = InAppAlertState.setPinned(ctx, g.groupId, !g.pinned)
                if (!ok) Toast.makeText(
                    ctx, "Pinned is full (max ${InAppAlertState.MAX_PINNED}) — unpin one first",
                    Toast.LENGTH_SHORT
                ).show()
            }
            // Dismiss a remainder card: remove ONLY its (unfollowed) events, so the
            // followed events shown separately in "Signed up Events" survive.
            val dismissCard: (InAppAlertGroup) -> Unit = { g ->
                InAppAlertState.dismissEvents(ctx, g.groupId, g.events.map { it.id })
                val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.cancel(g.groupId.hashCode())
            }
            if (signedUpCards.isNotEmpty()) {
                item(key = "signedup_header") {
                    Text(
                        "★ Signed up Events",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = gold,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp)
                    )
                }
                items(signedUpCards, key = { it.first }) { (_, group) ->
                    AlertGroupCard(group = group, nowMs = nowMs, signedUp = true,
                        onTogglePin = {}, onDismiss = {})
                }
            }
            if (pinnedCards.isNotEmpty()) {
                item(key = "pinned_header") {
                    val pinExpanded = PinnedSectionState.expanded.value
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable { PinnedSectionState.expanded.value = !pinExpanded }
                            .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.PushPin, null,
                            tint = silver,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Pinned (${pinnedCards.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = silver,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            if (pinExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (pinExpanded) "Collapse pinned" else "Expand pinned",
                            tint = silver,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                if (PinnedSectionState.expanded.value) {
                    items(pinnedCards, key = { it.first }) { (_, group) ->
                        AlertGroupCard(group = group, nowMs = nowMs, pinned = true,
                            onTogglePin = { togglePin(group) }, onDismiss = { dismissCard(group) })
                    }
                }
            }
            if ((signedUpCards.isNotEmpty() || pinnedCards.isNotEmpty()) &&
                (restCards.isNotEmpty() || removedCards.isNotEmpty())) {
                item(key = "rest_header") {
                    Text(
                        "Other notifications",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 2.dp)
                    )
                }
            }
            // Deleted events: red "Removed" cards, at the top of the normal area,
            // dismissable so the user can swipe the dead event away.
            items(removedCards, key = { it.first }) { (_, group) ->
                AlertGroupCard(group = group, nowMs = nowMs, signedUp = false, pinned = false,
                    removed = true, onTogglePin = {}, onDismiss = { dismissCard(group) })
            }
            items(restCards, key = { it.first }) { (_, group) ->
                AlertGroupCard(group = group, nowMs = nowMs, signedUp = false, pinned = false,
                    onTogglePin = { togglePin(group) }, onDismiss = { dismissCard(group) })
            }
        }
    }
}

/**
 * Collapse recurring event occurrences into one entry per series. VRChat writes
 * EVERY day of a repeating event as its own calendar event (a 50-occurrence flood
 * for a daily repeat), so same-title events are folded to the nearest UPCOMING
 * occurrence (or the most recent past one if none are upcoming). Single-title
 * events pass through unchanged, so non-recurring alerts are untouched. Order is
 * preserved by first appearance.
 */
private fun collapseRecurring(
    events: List<InAppAlertEvent>,
    nowMs: Long
): List<InAppAlertEvent> {
    if (events.size < 2) return events
    val byKey = LinkedHashMap<String, MutableList<InAppAlertEvent>>()
    for (e in events) {
        // SERIES id is the real identity — every occurrence of a repeat shares it,
        // and two DISTINCT series never collide on it (title-only keying merged
        // different events and flip-flopped which one a card showed). Fall back to
        // the normalized title only when there's no seriesId, then to the id.
        val key = when {
            !e.seriesId.isNullOrBlank() -> "s:${e.seriesId}"
            !e.eventTitle.isNullOrBlank() -> "t:${e.eventTitle.trim().lowercase()}"
            else -> "id:${e.id}"
        }
        byKey.getOrPut(key) { mutableListOf() }.add(e)
    }
    return byKey.values.map { occ ->
        if (occ.size == 1) occ[0]
        else occ.filter { it.startsAtMs > 0 && it.startsAtMs >= nowMs }.minByOrNull { it.startsAtMs }
            ?: occ.maxByOrNull { it.startsAtMs } ?: occ[0]
    }
}

private fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    // Future-aware: a FUTURE timestamp (delta < 0) renders "in Xd" instead of
    // clamping to "just now". Group calendar events use their scheduled start
    // (normally upcoming) as the display timestamp, so without this an event
    // tomorrow would read "just now" and never advance.
    val delta = nowMs - timestampMs
    val future = delta < 0
    val sec = kotlin.math.abs(delta) / 1000L
    val rel = when {
        sec < 5 -> return "just now"
        sec < 60 -> "${sec}s"
        sec < 3600 -> "${sec / 60}m"
        sec < 86400 -> "${sec / 3600}h"
        else -> "${sec / 86400}d"
    }
    return if (future) "in $rel" else "$rel ago"
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
private fun AlertGroupCard(
    group: InAppAlertGroup,
    nowMs: Long,
    signedUp: Boolean = false,
    pinned: Boolean = false,
    removed: Boolean = false,
    onTogglePin: () -> Unit = {},
    onDismiss: () -> Unit
) {
    // Accent treatment: signed-up (Added to Calendar) = GOLD, manually pinned =
    // SILVER, DELETED-from-VRChat = RED; all three get an accent bar/border + a
    // pill. Signed-up/pinned are protected from dismissal; a removed card is
    // dismissable (swipe the dead event away). (No emoji — pin is PushPin.)
    val gold = Color(0xFFFFC64B)
    val silver = Color(0xFFC7CDD9)
    val red = Color(0xFFE05561)
    // A removed card is styled special (red) but stays dismissable, so it is NOT
    // part of `special` (which gates dismissal protection below).
    val special = signedUp || pinned
    val accent = when {
        signedUp -> gold
        pinned -> silver
        removed -> red
        else -> silver
    }
    val decorated = special || removed
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    // The "Repeats" dialog's open-state lives HERE (card scope), NOT inside
    // AlertEventBody — that body is wrapped in key(event.id, event), so every
    // enrichment tick that changes the event data disposes+recreates it, which was
    // slamming the dialog shut. Card scope is keyed by groupId, so it survives.
    var occurrencesFor by remember { mutableStateOf<InAppAlertEvent?>(null) }
    occurrencesFor?.let { oe ->
        if (oe.recurring && !oe.seriesId.isNullOrBlank() && !oe.groupRefId.isNullOrBlank()) {
            EventOccurrencesDialog(
                groupId = oe.groupRefId!!,
                seriesId = oe.seriesId!!,
                seriesTitle = oe.eventTitle ?: "Repeating event",
                onDismiss = { occurrencesFor = null }
            )
        } else occurrencesFor = null
    }
    // Collapse recurring occurrences (VRChat auto-creates EVERY day of a repeat as
    // a separate calendar event — up to 50). Same-title events fold to the nearest
    // UPCOMING occurrence so the card shows ONE, not 50. Retroactive: fixes cards
    // that already flooded before the fire-time collapse landed.
    val displayEvents = remember(group.events, nowMs) { collapseRecurring(group.events, nowMs) }
    val eventCount = displayEvents.size
    val displayTitle = if (eventCount > 1) "${group.title} ($eventCount)" else group.title

    // Preview off the SOONEST event, not the last (the last was the farthest-out
    // occurrence → "in 48d"; the soonest is what the user cares about).
    val latest = displayEvents.minByOrNull { if (it.startsAtMs > 0) it.startsAtMs else Long.MAX_VALUE }
        ?: displayEvents.lastOrNull()
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
    val inviteMeTargets = displayEvents
        .filter { it.actionType == NotificationActionReceiver.ACTION_INVITE_ME && !it.actionData.isNullOrBlank() }
        .map { InstanceTarget(it.actionData!!, it.eventTitle ?: it.body, imageUrl = it.imageUrl.orEmpty()) }
        .distinctBy { it.location }
    val inviteUserData = displayEvents
        .firstOrNull { it.actionType == NotificationActionReceiver.ACTION_INVITE_USER && !it.actionData.isNullOrBlank() }
        ?.actionData
    val sharedOpenUrl = displayEvents.mapNotNull { it.url }.distinct().singleOrNull()
    val showPerEventOpen = sharedOpenUrl == null
    val headerOpenUrl = sharedOpenUrl
        ?: group.url?.takeIf { displayEvents.none { e -> e.url != null } }
    // "Join event": lists the hosting group's currently-open instances in the
    // same picker the multi-invite flow uses. null = closed; empty = fetched,
    // group has nothing open right now.
    var joinTargets by remember { mutableStateOf<List<InstanceTarget>?>(null) }
    var joinLoading by remember { mutableStateOf(false) }

    // Expanding an event/announcement card checks VRChat for changes (interested
    // count, edited times/description/thumbnail/languages) right then. 15s
    // per-group cooldown — deliberately SHORTER than the 60s tab loop's stamp
    // interval, because the loop constantly re-stamps the shared debounce map; a
    // 30s expand cooldown was silently no-oping half the time ("re-expanding
    // doesn't update"). Within the cooldown the data is at most 15s old anyway.
    // The global mutex + 2.5s spacing in the enricher still bounds spam.
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        if (!group.groupId.startsWith("event_grp_") &&
            !group.groupId.startsWith("announcement_grp_")) return@LaunchedEffect
        val refIds = group.events.mapNotNull { it.groupRefId }.distinct().take(2)
        // While the card stays expanded, keep the changeable info (interested
        // count, timing, banner, follow state, edits) fresh on a 10s cadence —
        // the effect is cancelled the moment it collapses, so the loop stops with
        // it. The ~9s per-group min lets each 10s tick through while the 55s tab
        // loop mostly no-ops for this group; the global mutex still bounds spam.
        while (true) {
            for (gid in refIds) {
                runCatching { com.vrca.vrchat.GroupAlertEnricher.enrich(ctx, gid, minIntervalMs = 9_000) }
            }
            kotlinx.coroutines.delay(10_000)
        }
    }

    fun doInstantAction(actionType: String, data: String) {
        actionSending = true
        scope.launch {
            val r = NotificationActionReceiver.perform(ctx, actionType, data)
            Toast.makeText(ctx, NotificationActionReceiver.feedback(actionType, r), Toast.LENGTH_LONG).show()
            actionSending = false
        }
    }

    fun openJoinPicker(groupRefId: String, eventLabel: String) {
        if (joinLoading) return
        joinLoading = true
        scope.launch {
            val locations = VrchatAuthManager.fetchGroupInstances(ctx, groupRefId)
            joinTargets = locations.map { InstanceTarget(it, eventLabel) }
            joinLoading = false
        }
    }

    if (showInstancePicker) {
        InstanceListDialog(
            title = group.title,
            targets = inviteMeTargets,
            onDismiss = { showInstancePicker = false }
        )
    }
    joinTargets?.let { targets ->
        InstanceListDialog(
            // Neutral title — the picker now serves both a LIVE event's "Join event"
            // and an announcement's "See active instances".
            title = "Active instances · ${group.title}",
            targets = targets,
            onDismiss = { joinTargets = null },
            emptyText = "This group has no open instances right now. The host may not have opened one yet — try Open in VRChat instead."
        )
    }

    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(
            // Faint warm tint pushes a signed-up card toward gold without hurting
            // legibility; normal cards keep surfaceVariant.
            containerColor = if (decorated)
                androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surfaceVariant, accent, 0.10f)
            else MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = if (decorated)
            Modifier.border(1.dp, accent.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
        else Modifier
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
                            if (decorated) accent else MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small
                        )
                )
                // Title block is the expand tap target. (Opening the group page
                // lives on the "Group" chip inside the event body, per feedback —
                // NOT on the title.)
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { expanded = !expanded }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            // Long notification titles ("Announcement from <long group
                            // name>", etc.) were cut with an ellipsis on ALL alert
                            // types — this header is shared — so allow a second line
                            // before ellipsizing.
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (decorated) {
                            Spacer(Modifier.width(6.dp))
                            Surface(color = accent.copy(alpha = 0.20f), shape = MaterialTheme.shapes.extraSmall) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                ) {
                                    when {
                                        signedUp -> Text(
                                            "★ Going",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = accent
                                        )
                                        removed -> Text(
                                            "Removed",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = accent
                                        )
                                        else -> {
                                            Icon(
                                                Icons.Filled.PushPin, null,
                                                modifier = Modifier.size(11.dp), tint = accent
                                            )
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                "Pinned",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = accent
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (!expanded && previewText.isNotBlank()) {
                        Text(
                            previewText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    // Ago row + a TINY pin toggle (shown in both collapsed and
                    // expanded states). Tapping pins/unpins this group to the
                    // "📌 Pinned" section. Gold when pinned. HIDDEN for signed-up
                    // (Added-to-Calendar) events — those auto-sort into the "Going"
                    // section, so a manual pin on them just fought the auto-sort;
                    // the pin returns once the event is removed from the calendar.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        latest?.let {
                            Text(
                                formatRelativeTime(it.timestampMs, nowMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (!signedUp && !removed) {
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(20.dp).clip(CircleShape).clickable { onTogglePin() },
                            contentAlignment = Alignment.Center) {
                            Icon(
                                if (pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                contentDescription = if (pinned) "Unpin" else "Pin",
                                modifier = Modifier.size(14.dp),
                                tint = if (pinned) silver else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        }
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
                // Dismiss — HIDDEN for signed-up OR manually-pinned cards so a user
                // can't accidentally swipe away something they pinned/are going to.
                // Unpinning (or Remove-from-Calendar) brings the dismiss button back.
                if (!special) {
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
                    // key() on the set of event ids forces the AnimatedVisibility
                    // content to repaint when a SECOND event fuses into the group
                    // (the content lambda otherwise skips re-running — same class of
                    // bug as the cycle-mute rows) so a newly-fused event shows up.
                    val fuseKey = displayEvents.joinToString(",") { it.id }
                    key(fuseKey) {
                    for ((idx, event) in displayEvents.withIndex()) {
                        // Per-event dismiss (X) when the card fuses MULTIPLE events —
                        // lets the user clear one without nuking the whole group.
                        val onEventDismiss: (() -> Unit)? =
                            if (displayEvents.size > 1) {
                                { InAppAlertState.dismissEvent(ctx, group.groupId, event.id) }
                            } else null
                        if (event.beforeText != null && event.afterText != null) {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    if (eventCount > 1) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "Change ${idx + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.weight(1f)
                                            )
                                            onEventDismiss?.let { d ->
                                                Box(
                                                    Modifier.size(20.dp).clip(CircleShape).clickable { d() },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Close,
                                                        contentDescription = "Remove this",
                                                        modifier = Modifier.size(14.dp),
                                                        tint = MaterialTheme.colorScheme.error
                                                    )
                                                }
                                            }
                                        }
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
                        } else if (event.body.isNotBlank() || event.imageUrl != null) {
                            // Keyed on the event's FULL data, not just its id: this
                            // content sits inside AnimatedVisibility, which (proven
                            // on-device — the cycle-mute saga) can skip repainting
                            // children whose params changed. Keying on the data
                            // forces recreation whenever enrichment updates ANY
                            // field (interested count, times, banner, languages) —
                            // without this, an enrichment sweep updated the state
                            // but the expanded card never visually changed.
                            key(event.id, event) {
                                AlertEventBody(
                                    event = event,
                                    nowMs = nowMs,
                                    groupKey = group.groupId,
                                    showOpen = showPerEventOpen && event.url != null,
                                    joinLoading = joinLoading,
                                    onJoinEvent = { grpId ->
                                        openJoinPicker(grpId, event.eventTitle ?: group.title)
                                    },
                                    onShowOccurrences = { occurrencesFor = event },
                                    onEventDismiss = onEventDismiss
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

/**
 * One event/announcement inside an alert group — the RICH renderer. Shows the
 * banner (from AlertImageStore's dismissal-scoped disk cache, so opening the menu
 * never re-downloads), category/access/platform/interested chips, a live status
 * chip (countdown → "Live now" → "Ended"), Starts/Ends/Posted lines, the body
 * (with read-more for long announcements), tappable "Visit link" buttons for URLs
 * in the body, and phase-appropriate actions: Add/Remove Calendar before start,
 * Join event while live. Plain events (no rich metadata) render exactly like the
 * old simple card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlertEventBody(
    event: InAppAlertEvent,
    nowMs: Long,
    groupKey: String,
    showOpen: Boolean,
    joinLoading: Boolean,
    onJoinEvent: (String) -> Unit,
    onShowOccurrences: () -> Unit = {},
    onEventDismiss: (() -> Unit)? = null
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var calSending by remember { mutableStateOf(false) }
    var bodyExpanded by remember { mutableStateOf(false) }

    // Tapping "↻ Repeats" opens the per-occurrence subscribe dialog (owned by the
    // parent card so an enrichment tick can't dismiss it). Only offered when we have
    // the stable ids it needs (group + series).
    val canShowOccurrences = event.recurring &&
        !event.seriesId.isNullOrBlank() && !event.groupRefId.isNullOrBlank()

    // Rich display needs only a known start time; the calendar ACTION additionally
    // needs the cal_/grp_ ids (checked at the button below).
    val isRichEvent = event.startsAtMs > 0L

    // For an ANNOUNCEMENT (not a rich event), offer the same instance picker events
    // get — but ONLY when the hosting group actually has an OPEN instance. Checked
    // once on expand (this body only renders while the card is expanded), so it's
    // one lightweight GET per opened announcement, not per row.
    var announcementInstanceCount by remember(event.id) { mutableStateOf(0) }
    if (!isRichEvent && event.groupRefId != null) {
        LaunchedEffect(event.id, event.groupRefId) {
            val insts = runCatching {
                VrchatAuthManager.fetchGroupInstances(ctx, event.groupRefId!!)
            }.getOrNull()
            announcementInstanceCount = insts?.size ?: 0
        }
    }
    val phase = eventPhase(event.startsAtMs, event.endsAtMs, nowMs)
    val bodyUrls = remember(event.body) { extractBodyUrls(event.body) }
    val longBody = event.body.length > 320
    val displayBody = if (longBody && !bodyExpanded) event.body.take(300).trimEnd() + "…" else event.body
    val langCodes = remember(event.languages) { languageCodes(event.languages) }
    // Exactly ONE language chip can show its device-language translation at a
    // time: tapping a chip translates it and immediately reverts any other that
    // was still in its 5s window. langTapTick restarts the 5s timer on every tap
    // (including re-tapping the same chip).
    var translatedLang by remember { mutableStateOf<String?>(null) }
    var langTapTick by remember { mutableStateOf(0) }
    LaunchedEffect(langTapTick) {
        if (translatedLang != null) {
            delay(5_000)
            translatedLang = null
        }
    }
    val onLangTap: (String) -> Unit = { code ->
        translatedLang = code
        langTapTick++
    }
    val startingSoon = event.startsAtMs - nowMs < 2L * 60 * 60 * 1000
    val (phaseLabel, phaseColor) = when (phase) {
        EventPhase.UPCOMING -> "Starts ${formatRelativeTime(event.startsAtMs, nowMs)}" to
            (if (startingSoon) Color(0xFFFFB300) else MaterialTheme.colorScheme.primary)
        EventPhase.LIVE -> "Live now" to Color(0xFF4CAF50)
        EventPhase.ENDED -> "Ended" to MaterialTheme.colorScheme.outline
    }
    // The status/category/access chips render either ON the banner's bottom scrim
    // (solid dark chips, identity-card style) or as a normal row when there's no
    // banner — same content, one definition.
    val richChips: @Composable (Boolean) -> Unit = { solid ->
        EventMetaChip(phaseLabel, phaseColor, bold = true, solid = solid)
        event.category?.let {
            EventMetaChip(prettyEventCategory(it), MaterialTheme.colorScheme.tertiary, solid = solid)
        }
        // Recurring indicator does NOT go here — it crowded the status row. On a
        // banner it rides the image's empty TOP-LEFT corner; without a banner it
        // joins the muted meta line (interested · posted · repeats). See below.
        event.accessType?.let {
            val label = it.replaceFirstChar { c -> c.uppercase() }
            val color = when (it.lowercase()) {
                "public" -> Color(0xFF4CAF50)
                "group" -> Color(0xFFAB47BC)
                else -> MaterialTheme.colorScheme.outline
            }
            EventMetaChip(
                label, color, solid = solid,
                onClick = event.groupRefId?.let { gid ->
                    {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://vrchat.com/home/group/$gid"))
                        )
                    }
                }
            )
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Banner at the image's OWN ratio (FillWidth + wrapped height) so the
            // WHOLE picture shows — any fixed-ratio Crop box cut edges because
            // creators upload arbitrary ratios (tester-confirmed twice). Overlays
            // ride the banner like the identity header card: language chips
            // top-right (native names, as VRChat renders them), status/category/
            // access chips + platform symbols on a bottom scrim. Cached file
            // first; Coil's session-authed loader is the network fallback.
            if (!event.imageUrl.isNullOrBlank()) {
                val model: Any = AlertImageStore.resolve(ctx, event.imageUrl) ?: event.imageUrl
                Box(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)) {
                    coil.compose.AsyncImage(
                        model = model,
                        imageLoader = com.vrca.admin.VrchatImageLoader.get(ctx),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth()
                    )
                    // Recurring indicator on the banner's empty TOP-LEFT corner
                    // (keeps it off the crowded status row).
                    if (event.recurring) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = MaterialTheme.shapes.extraSmall,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(6.dp)
                                .then(if (canShowOccurrences) Modifier.clickable { onShowOccurrences() } else Modifier)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    if (canShowOccurrences) "↻ Repeats · view dates" else "↻ Repeats",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                    if (langCodes.isNotEmpty()) {
                        // Right-aligned STACK (not a row): native sign-language
                        // names ("American Sign Language", 日本手話) are long, so a
                        // horizontal row overflowed the banner on 2-3 languages.
                        Column(
                            Modifier.align(Alignment.TopEnd).padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(1.dp),
                            horizontalAlignment = Alignment.End
                        ) {
                            for (code in langCodes.take(3)) {
                                LanguageChip(code, overlay = true,
                                    translated = translatedLang == code,
                                    onTap = { onLangTap(code) })
                            }
                        }
                    }
                    if (isRichEvent) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))
                                    )
                                )
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                richChips(true)
                                Spacer(Modifier.weight(1f))
                                PlatformSymbols(event.platforms, overlay = true)
                            }
                        }
                    }
                }
            }
            Column(Modifier.padding(12.dp)) {
                // Title row + optional per-event dismiss X (multi-event cards).
                if (!event.eventTitle.isNullOrBlank() || onEventDismiss != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            event.eventTitle.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        onEventDismiss?.let { d ->
                            Box(
                                Modifier.size(22.dp).clip(CircleShape).clickable { d() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = "Remove this event",
                                    modifier = Modifier.size(15.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
                // (No host/organizer line — deliberately not shown on notifications.)
                // No banner to overlay on → ONE wrapping flow with everything
                // together (status/category/access chips, platform symbols,
                // language chips) so nothing sits disconnected on its own line
                // with weird gaps, then a thin divider separating this meta
                // block from the description. The access chip is the tap target
                // for the group's web page either way.
                if (isRichEvent && event.imageUrl.isNullOrBlank()) {
                    // TWO tight rows: (1) status/category/access chips + platform
                    // symbols on ONE plain Row — a plain Row (not FlowRow) packs
                    // them left, vertically centered, guaranteed on one line
                    // (they're short); the earlier FlowRow / weight-Spacer pushed
                    // the platforms to the far right and off the chips' baseline.
                    // (2) language chips right below, only 3dp under the chips.
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            richChips(false)
                            PlatformSymbols(event.platforms, overlay = false)
                        }
                        if (langCodes.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (code in langCodes.take(6)) {
                                    LanguageChip(code, overlay = false,
                                        translated = translatedLang == code,
                                        onTap = { onLangTap(code) })
                                }
                            }
                        }
                    }
                    Divider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                if (displayBody.isNotBlank() && displayBody != event.eventTitle) {
                    Text(
                        displayBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (longBody) {
                        Text(
                            if (bodyExpanded) "Show less" else "Read more",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .clickable { bodyExpanded = !bodyExpanded }
                        )
                    }
                }
                if (isRichEvent) {
                    // One compact timing line ("Jul 5 · 3:05 PM → Jul 8 · 5:05 PM")
                    // and one muted meta line (posted · interested · platforms) —
                    // replaces the old stacked Starts/Ends/Posted block that read
                    // as bolted-on.
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Schedule, null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            buildString {
                                append(eventDateTime(event.startsAtMs))
                                if (event.endsAtMs > 0) append("  →  ${eventDateTime(event.endsAtMs)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val metaParts = buildList {
                        if (event.interestedCount >= 0) add("${event.interestedCount} interested")
                        if (event.createdAtMs > 0) add("Posted ${eventDate(event.createdAtMs)}")
                    }
                    if (metaParts.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            metaParts.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    // Recurring indicator on the meta block ONLY when there's no banner
                    // to carry it top-left. Tappable → the per-occurrence dates dialog.
                    if (event.recurring && event.imageUrl.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                            shape = MaterialTheme.shapes.small,
                            modifier = if (canShowOccurrences) Modifier.clickable { onShowOccurrences() } else Modifier
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.Repeat, null, modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (canShowOccurrences) "Repeats · view dates" else "Repeats",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                } else {
                    // Plain alerts keep the relative-time footer. Rich events don't
                    // need it — the status chip + timing line already say when.
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatRelativeTime(event.timestampMs, nowMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // "Visit link" buttons for URLs embedded in announcement bodies —
                // tappable instead of raw blue text.
                for (url in bodyUrls) {
                    Spacer(Modifier.height(8.dp))
                    CompactAlertButton(
                        label = remember(url) {
                            val host = try { Uri.parse(url).host ?: url } catch (_: Throwable) { url }
                            "Visit $host"
                        },
                        icon = Icons.Filled.Link,
                        prominent = false,
                        enabled = true,
                        onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    )
                }
                // Announcement from a group that currently has open instances: the
                // same picker events use, shown ONLY when there's an active instance.
                if (!isRichEvent && event.groupRefId != null && announcementInstanceCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    CompactAlertButton(
                        label = if (joinLoading) "Finding instances..." else "See active instances",
                        icon = Icons.AutoMirrored.Filled.Login,
                        prominent = true,
                        enabled = !joinLoading,
                        onClick = { onJoinEvent(event.groupRefId) }
                    )
                }
                // Deleted from VRChat: no calendar/join actions — the event is gone.
                // A clear red notice replaces the buttons; the card is dismissable.
                if (event.removed) {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFE05561).copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Filled.EventBusy, null,
                                tint = Color(0xFFE05561), modifier = Modifier.size(16.dp)
                            )
                            Text(
                                "This event was removed from VRChat",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFE05561)
                            )
                        }
                    }
                }
                // Phase-appropriate calendar/join actions (suppressed once removed).
                if (!event.removed && isRichEvent && event.groupRefId != null) {
                    when (phase) {
                        EventPhase.UPCOMING -> if (event.eventRefId != null) {
                            val onCal = event.following == true
                            Spacer(Modifier.height(8.dp))
                            CompactAlertButton(
                                label = when {
                                    calSending -> if (onCal) "Removing..." else "Adding..."
                                    onCal -> "Remove from Calendar"
                                    else -> "Add to Calendar"
                                },
                                icon = if (onCal) Icons.Filled.EventBusy else Icons.Filled.CalendarMonth,
                                prominent = !onCal,
                                enabled = !calSending,
                                onClick = {
                                    calSending = true
                                    val target = !onCal
                                    // Record the user's decision PERSISTENTLY and per
                                    // SERIES so a later enrich sweep can't flip it back
                                    // (the server perpetually reports the creator's own
                                    // events as followed) and so a recurring series is
                                    // toggled as ONE thing. Optimistically apply it to
                                    // every occurrence right now.
                                    val followKey = InAppAlertState.followSeriesKey(
                                        event.eventTitle, event.eventRefId
                                    )
                                    InAppAlertState.recordFollowToggle(ctx, followKey, target)
                                    InAppAlertState.applyFollowToSeries(
                                        ctx, groupKey, event.eventTitle, event.eventRefId, target
                                    )
                                    scope.launch {
                                        val r = VrchatAuthManager.setCalendarEventFollowing(
                                            ctx, event.groupRefId, event.eventRefId!!, target
                                        )
                                        if (r.ok) {
                                            // Adding to calendar auto-UNPINS: a signed-up
                                            // event lives in the "★ Signed up Events"
                                            // section (above Pinned), so keeping it manually
                                            // pinned too caused it to flip between sections.
                                            if (target) InAppAlertState.setPinned(ctx, groupKey, false)
                                            // Schedule / cancel the local reminder that
                                            // pings the phone ~10 min before it starts.
                                            if (target) {
                                                com.vrca.vrchat.EventReminderScheduler.schedule(
                                                    ctx, event.eventRefId!!,
                                                    event.eventTitle ?: "Your event",
                                                    event.startsAtMs, event.url
                                                )
                                            } else {
                                                com.vrca.vrchat.EventReminderScheduler.cancel(ctx, event.eventRefId!!)
                                            }
                                            Toast.makeText(
                                                ctx,
                                                if (target) "Added to your VRChat calendar"
                                                else "Removed from your VRChat calendar",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            // API rejected it — revert the optimistic
                                            // override + display so the card reflects reality.
                                            InAppAlertState.recordFollowToggle(ctx, followKey, onCal)
                                            InAppAlertState.applyFollowToSeries(
                                                ctx, groupKey, event.eventTitle, event.eventRefId, onCal
                                            )
                                            Toast.makeText(
                                                ctx,
                                                r.error ?: "Couldn't update your calendar",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        calSending = false
                                    }
                                }
                            )
                        }
                        EventPhase.LIVE -> {
                            Spacer(Modifier.height(8.dp))
                            CompactAlertButton(
                                label = if (joinLoading) "Finding instances..." else "Join event",
                                icon = Icons.AutoMirrored.Filled.Login,
                                prominent = true,
                                enabled = !joinLoading,
                                onClick = { onJoinEvent(event.groupRefId) }
                            )
                        }
                        EventPhase.ENDED -> { /* concluded — no action */ }
                    }
                }
                if (showOpen && event.url != null) {
                    Spacer(Modifier.height(8.dp))
                    CompactAlertButton(
                        label = "Open in VRChat",
                        icon = Icons.Filled.OpenInNew,
                        prominent = false,
                        enabled = true,
                        onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(event.url))) }
                    )
                }
            }
        }
    }
}

/** Small tinted status/metadata chip on rich event alerts. [onClick] makes it a
 *  tap target (the access chip opens the hosting group's web page). [solid]
 *  renders the dark-translucent variant used ON the banner's bottom scrim,
 *  where the usual 16%-alpha tint would be illegible. */
@Composable
private fun EventMetaChip(
    label: String,
    color: Color,
    bold: Boolean = false,
    solid: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val container = if (solid) Color.Black.copy(alpha = 0.55f) else color.copy(alpha = 0.16f)
    // Fixed height + centered single-line text so every chip is the SAME size
    // regardless of script (CJK glyphs are taller than Latin — variable padding
    // made mixed-language chip rows look ragged).
    val content: @Composable () -> Unit = {
        Box(
            Modifier.height(22.dp).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
                color = color,
                maxLines = 1
            )
        }
    }
    // Modifier.clickable (NOT Surface(onClick=...)): a clickable Surface enforces
    // Material3's 48dp minimum interactive size, which padded each tappable chip
    // to 48dp tall and blew a huge invisible gap between the chip rows. clickable
    // keeps the chip its true 22dp height.
    Surface(
        color = container,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    ) { content() }
}

/** Language chip — shows the NATIVE name ("日本語", "日本手話") like VRChat's
 *  website; when [translated] it shows the device-language name ("Japanese",
 *  "Japanese Sign Language" — VRChat's in-client localization style). Controlled:
 *  the parent tracks which single chip is translated (tapping one reverts any
 *  other still in its 5s window) and the 5s timer. Fixed 22dp height so mixed
 *  Latin/CJK chips line up. Overlay variant sits on the banner's top-right. */
@Composable
private fun LanguageChip(code: String, overlay: Boolean, translated: Boolean, onTap: () -> Unit) {
    val label = if (translated) deviceLanguageName(code) else nativeLanguageName(code)
    Surface(
        // Modifier.clickable, not Surface(onClick=): avoids the 48dp minimum
        // interactive size that padded these chips and blew the row gap open.
        color = if (overlay) Color.Black.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f),
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.clickable(onClick = onTap)
    ) {
        Box(
            Modifier.height(20.dp).padding(horizontal = 7.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (overlay) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

/** Platform support as highlighted circular brand symbols — Windows flag (blue),
 *  Android robot (green), Apple mark (light) — matching VRChat's own platform
 *  badges (per user reference; a monitor/phone glyph read as wrong). */
@Composable
private fun PlatformSymbols(csv: String?, overlay: Boolean) {
    val platforms = csv?.split(",")?.mapNotNull { raw ->
        when (raw.trim().lowercase()) {
            "standalonewindows", "pc", "windows" -> "pc"
            "android", "quest" -> "android"
            "ios" -> "ios"
            else -> null
        }
    }?.distinct().orEmpty()
    if (platforms.isEmpty()) return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (p in platforms) {
            val tint = when (p) {
                "pc" -> Color(0xFF2196F3)      // Windows blue
                "android" -> Color(0xFF3DDC84) // Android brand green
                else -> Color(0xFFE0E0E0)      // Apple light grey
            }
            Surface(
                shape = CircleShape,
                color = if (overlay) Color.Black.copy(alpha = 0.55f) else tint.copy(alpha = 0.18f),
                modifier = Modifier.size(22.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    when (p) {
                        "pc" -> Icon(
                            androidx.compose.ui.res.painterResource(com.vrca.R.drawable.ic_platform_windows),
                            contentDescription = "PC", tint = tint, modifier = Modifier.size(12.dp)
                        )
                        "android" -> Icon(
                            Icons.Filled.Android,
                            contentDescription = "Quest", tint = tint, modifier = Modifier.size(14.dp)
                        )
                        else -> Icon(
                            androidx.compose.ui.res.painterResource(com.vrca.R.drawable.ic_platform_apple),
                            contentDescription = "iOS", tint = tint, modifier = Modifier.size(12.dp)
                        )
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

/** A target instance for the invite/history picker. [timeLines] (history only) shows
 *  each VISIT's join/left times (newest first, one line per session) so users can
 *  cross-reference what they played and when, and so rejoining a place keeps both
 *  visits instead of resetting. */
private data class InstanceTarget(
    val location: String,
    val label: String,
    val timeLines: List<String> = emptyList(),
    // Known world thumbnail (from a stored alert/history entry) so the row can
    // show the cached image INSTANTLY while the live info fetch is in flight.
    val imageUrl: String = "",
    // Pre-stored API instance type ("public"/"friends"/"hidden"/"group"/"invite"/
    // "invite+") from history, so the type chip is correct instantly without waiting
    // on the live fetch (parseInstanceType also derives it from the location as a
    // fallback). Blank for non-history targets.
    val instanceType: String = ""
)

private fun clockTime(ms: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(ms))

/** "Jul 5 · 10:22 AM" — compact begin/end stamps on rich event alerts (the
 *  Posted line carries the year; events are near-term so the timing line
 *  stays short). */
private fun eventDateTime(ms: Long): String =
    java.text.SimpleDateFormat("MMM d · h:mm a", java.util.Locale.US).format(java.util.Date(ms))

/** "Jul 4, 2026" — the Posted line. */
private fun eventDate(ms: Long): String =
    java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.US).format(java.util.Date(ms))

/** Where an event is in its lifecycle right now. */
private enum class EventPhase { UPCOMING, LIVE, ENDED }

private fun eventPhase(startsAtMs: Long, endsAtMs: Long, nowMs: Long): EventPhase = when {
    startsAtMs <= 0L -> EventPhase.UPCOMING
    nowMs < startsAtMs -> EventPhase.UPCOMING
    endsAtMs > 0L -> if (nowMs <= endsAtMs) EventPhase.LIVE else EventPhase.ENDED
    // No published end time: call it live for 4h after start, then ended.
    else -> if (nowMs - startsAtMs <= 4L * 60 * 60 * 1000) EventPhase.LIVE else EventPhase.ENDED
}

/**
 * "Repeats" dialog — lists every occurrence of a recurring series with its start time
 * and a per-occurrence Subscribe toggle (matches VRChat's per-occurrence follow model).
 * Styled like the invite/instance picker. Occurrence existence + timing come FREE from
 * the group calendar list (reconciled into [EventSeriesStore], which also flags
 * deletions); follow state is filled in lazily (nearest-upcoming first, paced) since
 * the list omits it. Deleted occurrences show a red "Removed" tag; a fully-removed
 * series shows a notice.
 */
@Composable
private fun EventOccurrencesDialog(
    groupId: String,
    seriesId: String,
    seriesTitle: String,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var storeTick by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    // Per-row in-flight subscribe toggles (occurrenceId -> busy).
    val busy = remember { mutableStateMapOf<String, Boolean>() }
    LaunchedEffect(Unit) { while (true) { delay(1000); nowMs = System.currentTimeMillis() } }

    // (a) reconcile the occurrence list (free deletion + timing), then (b) lazily fill
    // in follow state for occurrences that don't have it yet (bounded + paced).
    LaunchedEffect(groupId, seriesId) {
        val list = VrchatAuthManager.fetchGroupCalendarEvents(ctx, groupId, 100)
        if (list != null) {
            val live = ArrayList<Triple<String, Long, Long>>()
            for (i in 0 until list.length()) {
                val ev = list.optJSONObject(i) ?: continue
                if (com.vrca.vrchat.GroupAlertEnricher.extractSeriesId(ev) != seriesId) continue
                val id = ev.optString("id", "")
                if (id.isBlank()) continue
                live.add(Triple(
                    id,
                    com.vrca.vrchat.GroupAlertEnricher.parseTimestampMs(ev.optString("startsAt", "")),
                    com.vrca.vrchat.GroupAlertEnricher.parseTimestampMs(ev.optString("endsAt", ""))
                ))
            }
            EventSeriesStore.reconcileFromList(ctx, groupId, seriesId, live, listOk = true)
            storeTick++
        }
        val need = EventSeriesStore.idsNeedingFollow(ctx, groupId, seriesId, staleMs = 5 * 60 * 1000L).take(20)
        for (id in need) {
            if (busy[id] == true) continue // the user is toggling this one — don't clobber it
            val res = VrchatAuthManager.fetchCalendarEventResult(ctx, groupId, id)
            if (res.status == VrchatAuthManager.CalendarEventStatus.FOUND && busy[id] != true) {
                val f = res.event?.let { com.vrca.vrchat.GroupAlertEnricher.extractEventFollowing(it) } ?: false
                EventSeriesStore.setFollowing(ctx, groupId, seriesId, id, f)
                storeTick++
            }
            delay(300)
        }
        loading = false
    }

    // Re-read once a minute too so ended occurrences drop as time passes.
    val occs = remember(storeTick, nowMs / 60000) { EventSeriesStore.occurrences(ctx, groupId, seriesId) }
    val allRemoved = remember(storeTick) { EventSeriesStore.allDeleted(ctx, groupId, seriesId) }
    val goingCount = occs.count { it.following == true && !it.deleted }

    fun toggle(occ: EventSeriesStore.Occurrence) {
        if (busy[occ.id] == true) return
        val target = occ.following != true
        busy[occ.id] = true
        // Optimistic — write to the store now so the row flips instantly.
        EventSeriesStore.setFollowing(ctx, groupId, seriesId, occ.id, target)
        storeTick++
        scope.launch {
            val r = VrchatAuthManager.setCalendarEventFollowing(ctx, groupId, occ.id, target)
            if (!r.ok) {
                EventSeriesStore.setFollowing(ctx, groupId, seriesId, occ.id, !target) // revert
                storeTick++
                Toast.makeText(ctx, r.error ?: "Couldn't update your calendar", Toast.LENGTH_LONG).show()
            }
            busy[occ.id] = false
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ElevatedCard(
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 6.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 620.dp)
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Repeat, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(seriesTitle.ifBlank { "Repeating event" },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append("${occs.count { !it.deleted }} dates")
                                if (goingCount > 0) append(" · going to $goingCount")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(onClick = onDismiss, shape = CircleShape,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        modifier = Modifier.size(34.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, "Close", tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (loading && occs.isEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Loading occurrences...", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(14.dp))
                when {
                    allRemoved -> Text(
                        "This event series was removed from VRChat.",
                        style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE05561))
                    occs.isEmpty() && !loading -> Text(
                        "No upcoming occurrences.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Column(
                        Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (occ in occs) OccurrenceRow(occ, nowMs, busy[occ.id] == true) { toggle(occ) }
                    }
                }
                if (loading && occs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Checking which you're subscribed to...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun OccurrenceRow(
    occ: EventSeriesStore.Occurrence,
    nowMs: Long,
    busy: Boolean,
    onToggle: () -> Unit
) {
    val red = Color(0xFFE05561)
    val green = Color(0xFF4CAF50)
    val following = occ.following == true
    val phase = eventPhase(occ.startsAtMs, occ.endsAtMs, nowMs)
    val accent = when {
        occ.deleted -> red
        following -> green
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp).height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).fillMaxHeight().clip(CircleShape)
                .background(accent.copy(alpha = 0.85f)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (occ.startsAtMs > 0L) eventDateTime(occ.startsAtMs) else "Time TBD",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (occ.deleted) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (occ.deleted) TextDecoration.LineThrough else null
                )
                Text(
                    when {
                        occ.deleted -> "Removed from VRChat"
                        phase == EventPhase.LIVE -> "Live now"
                        occ.startsAtMs > 0L -> formatRelativeTime(occ.startsAtMs, nowMs)
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        occ.deleted -> red
                        phase == EventPhase.LIVE -> green
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                occ.deleted -> Surface(color = red.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
                    Text("Removed", style = MaterialTheme.typography.labelMedium, color = red,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                }
                else -> Surface(
                    onClick = onToggle,
                    shape = MaterialTheme.shapes.small,
                    color = if (following) green.copy(alpha = 0.16f)
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    enabled = !busy
                ) {
                    Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp,
                                color = if (following) green else MaterialTheme.colorScheme.primary)
                        } else {
                            Icon(if (following) Icons.Filled.Check else Icons.Filled.Add, null,
                                modifier = Modifier.size(15.dp),
                                tint = if (following) green else MaterialTheme.colorScheme.primary)
                        }
                        Text(if (following) "Going" else "Subscribe",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (following) green else MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** Pull tappable links out of an announcement body ("Visit link" buttons). */
private fun extractBodyUrls(body: String): List<String> =
    Regex("https?://[^\\s)\\]}>\"']+").findAll(body)
        .map { it.value.trimEnd('.', ',', ';', ':') }
        .distinct()
        .take(3)
        .toList()

/** VRChat calendar category ids → display names ("film_media" → "Film & Media"). */
private fun prettyEventCategory(raw: String): String = when (raw.lowercase().trim()) {
    "film_media", "film_and_media" -> "Film & Media"
    else -> raw.split('_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
}

/**
 * VRChat's language codes → each language's NATIVE name, exactly how VRChat's
 * own site renders them (日本語, English, 한국어 — NOT localized to the device
 * language, which made "English" show as 英語 on a Japanese-locale phone).
 * Static map first (VRChat's known language tags incl. sign languages), then
 * Locale-native resolution, then the uppercased code as last resort.
 */
private val vrcLanguageNames = mapOf(
    "eng" to "English", "kor" to "한국어", "rus" to "Русский", "spa" to "Español",
    "por" to "Português", "zho" to "中文", "deu" to "Deutsch", "jpn" to "日本語",
    "fra" to "Français", "swe" to "Svenska", "nld" to "Nederlands", "pol" to "Polski",
    "dan" to "Dansk", "nor" to "Norsk", "ita" to "Italiano", "tha" to "ภาษาไทย",
    "fin" to "Suomi", "hun" to "Magyar", "ces" to "Čeština", "tur" to "Türkçe",
    "ara" to "العربية", "ron" to "Română", "vie" to "Tiếng Việt", "ukr" to "Українська",
    "heb" to "עברית", "ind" to "Bahasa Indonesia", "hmn" to "Hmong", "tgl" to "Tagalog",
    "mlt" to "Malti",
    // Sign languages — VRChat's tags with the EXACT display names VRChat's own
    // site uses (tester-confirmed: jsl renders as 日本手話, NOT "JSL" — never
    // shorten these; no Locale resolution exists for them).
    "ase" to "American Sign Language", "bfi" to "British Sign Language",
    "dse" to "Nederlandse Gebarentaal", "fsl" to "Langue des Signes Française",
    "jsl" to "日本手話", "kvk" to "한국수어"
)

private fun languageCodes(csv: String?): List<String> =
    csv?.split(",")?.mapNotNull { raw ->
        raw.trim().lowercase().removePrefix("language_").ifBlank { null }
    }?.distinct().orEmpty()

/** The language's NATIVE name ("日本語", "日本手話") — VRChat's website style. */
private fun nativeLanguageName(code: String): String =
    vrcLanguageNames[code] ?: run {
        // Native-name resolution: display the language IN ITSELF.
        val loc = java.util.Locale(code)
        val resolved = try { loc.getDisplayLanguage(loc) } catch (_: Throwable) { "" }
        if (resolved.isNotBlank() && !resolved.equals(code, ignoreCase = true)) resolved
        else code.uppercase()
    }

// Sign languages can't be resolved through Locale, so their translated names are
// English (the best universal fallback — spoken languages DO localize properly).
private val signLanguageTranslatedNames = mapOf(
    "ase" to "American Sign Language", "bfi" to "British Sign Language",
    "dse" to "Dutch Sign Language", "fsl" to "French Sign Language",
    "jsl" to "Japanese Sign Language", "kvk" to "Korean Sign Language"
)

/** The language's name translated into the DEVICE language ("Japanese" on an
 *  English phone, 日本語 on a Japanese phone) — VRChat's in-client style. */
private fun deviceLanguageName(code: String): String =
    signLanguageTranslatedNames[code] ?: run {
        val resolved = try { java.util.Locale(code).displayLanguage } catch (_: Throwable) { "" }
        if (resolved.isNotBlank() && !resolved.equals(code, ignoreCase = true)) resolved
        else code.uppercase()
    }

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
    onDismiss: () -> Unit,
    emptyText: String = "Nothing to show yet.",
    // Live pickers (invite / join) re-fetch every 15s so occupancy + open/closed
    // track reality while the menu is open. Instance HISTORY doesn't need live
    // occupancy — just "is it still joinable?" — so it does a ONE-TIME grab (no
    // 15s loop), cutting its calls to a single pass.
    liveRefresh: Boolean = true
) {
    val ctx = LocalContext.current
    // Resolved instance info, filled in INCREMENTALLY (newest target first) so the
    // list renders immediately and each row fills in as its fetch lands — the user
    // is never stuck on a full-screen spinner waiting for every instance.
    var infos by remember { mutableStateOf<Map<String, VrchatAuthManager.InstanceInfo>>(emptyMap()) }
    // Key on the set of target locations so a NEWLY-joined instance (the targets list
    // grows while the dialog is open) gets fetched instead of sitting on "Checking
    // (7/8)" forever. The loop also re-fetches every 15s so live occupancy / open-or-
    // closed status track reality while the dialog stays open ("an instance updates
    // while I'm in the menu" no longer needs a reopen). Old infos are kept across
    // re-keys so rows don't flash back to a spinner.
    val locationsKey = targets.joinToString("|") { it.location }
    LaunchedEffect(locationsKey) {
        while (true) {
            // targets are ordered current → most-recently-left (newest to oldest), so
            // resolving them in order fills/refreshes the list top-down.
            for (t in targets) {
                val info = VrchatAuthManager.fetchInstanceInfo(ctx, t.location)
                infos = infos + (t.location to info)
                // Persist the world image so future opens render it INSTANTLY from
                // disk instead of re-downloading (the "thumbnails reload every time
                // you open the menu" jank): cache the bytes, and record the URL on
                // whatever owns this location (a 24h history entry and/or an invite
                // alert event) so the file stays referenced until that owner goes.
                if (info.worldImageUrl.isNotBlank()) {
                    AlertImageStore.ensureCached(ctx, info.worldImageUrl)
                    InAppAlertState.setInviteTargetImage(ctx, t.location, info.worldImageUrl)
                }
                // Persist the freshest world name + thumbnail + type onto the history
                // entry (no-op for non-history targets) so a later cold open is instant.
                if (info.worldImageUrl.isNotBlank() || info.instanceType.isNotBlank()) {
                    InstanceHistoryStore.updateInfo(
                        ctx, t.location,
                        worldName = info.worldName,
                        imageUrl = info.worldImageUrl,
                        instanceType = info.instanceType
                    )
                }
            }
            if (!liveRefresh) break // history: one grab, no continuous re-fetch
            delay(15_000)
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
                        emptyText,
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
        (info?.instanceType ?: "").ifBlank { target.instanceType },
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
                // Live info's image first; else the STORED url from the alert /
                // history entry so the thumb shows instantly while info resolves.
                // Either way prefer the on-disk cached file over a network load.
                val img = info?.worldImageUrl.orEmpty().ifBlank { target.imageUrl }
                if (img.isNotBlank()) {
                    val model: Any = AlertImageStore.resolve(ctx, img) ?: img
                    coil.compose.AsyncImage(
                        model = model,
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
                    // Joinable = SOLID primaryContainer so it reads unmistakably as an
                    // active button. A faint alpha-tinted primary (the old 0.16f wash)
                    // looked like "just a different grey" next to the disabled state, so
                    // a loaded, open, populated instance's invite button appeared dead.
                    color = if (canJoin) MaterialTheme.colorScheme.primaryContainer
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
                                tint = if (canJoin) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
            // Join/left times — one full-width line per VISIT (newest first). The clock
            // icon sits on the first line; later sessions align under it. Predictable
            // max length, so even at "12:00 AM" extremes nothing clips.
            if (target.timeLines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                target.timeLines.forEachIndexed { i, line ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (i == 0) {
                            Icon(
                                Icons.Filled.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                        } else {
                            Spacer(Modifier.width(13.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        Text(
                            line,
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
