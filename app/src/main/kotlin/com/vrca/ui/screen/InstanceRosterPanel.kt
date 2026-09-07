package com.vrca.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vrca.BuildConfig
import com.vrca.vrchat.InstanceRosterManager

/**
 * "Who's in your instance" roster — the headline headset feature, fed by the
 * VRChat log reader ([InstanceRosterManager]). Lives in the far-right column of
 * the headset Home. Renders one of four states: needs file access, waiting for a
 * log, not in a world, or the live member list (name + platform).
 *
 * On non-headset builds the manager is never started, so this shows the "coming
 * soon" placeholder (it isn't placed on phone Home anyway).
 */
@Composable
fun InstanceRosterPanel(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (BuildConfig.IS_HEADSET_BUILD) InstanceRosterManager.start(ctx)
    }
    val ui by InstanceRosterManager.flow.collectAsState()
    // SAF fallback: let the user grant VRChat's log folder when direct file
    // access to Android/data is blocked (Android 11+ / most Horizon OS).
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) InstanceRosterManager.setSafFolder(ctx, uri) }

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header: title + live count.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("In your instance", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                if (ui.status == InstanceRosterManager.Status.LIVE) {
                    Text(
                        "${ui.members.size}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (ui.worldName != null && ui.status == InstanceRosterManager.Status.LIVE) {
                Text(
                    ui.worldName!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when (ui.status) {
                InstanceRosterManager.Status.NEEDS_PERMISSION -> AccessState(
                    ctx = ctx,
                    lead = "Give VRC-A access to VRChat's log so it can show who's in your instance.",
                    onPickFolder = { pickFolder.launch(null) }
                )
                InstanceRosterManager.Status.NO_LOG -> AccessState(
                    ctx = ctx,
                    lead = "No VRChat log yet. Two things to check:",
                    showChecklist = true,
                    onPickFolder = { pickFolder.launch(null) }
                )
                InstanceRosterManager.Status.IDLE -> HintState(
                    "Not in a world right now. Join an instance to see who's in it."
                )
                InstanceRosterManager.Status.LIVE -> {
                    if (ui.members.isEmpty()) {
                        HintState("You're the only one here so far.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ui.members.forEach { m -> MemberRow(m) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessState(
    ctx: android.content.Context,
    lead: String,
    showChecklist: Boolean = false,
    onPickFolder: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            lead,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showChecklist) {
            Text(
                "1. In VRChat: Settings -> Debug -> set Logging to FULL, then rejoin your world.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "2. If it still says this, pick VRChat's log folder below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!InstanceRosterManager.hasStoragePermission()) {
            Button(
                onClick = {
                    runCatching {
                        ctx.startActivity(
                            InstanceRosterManager.allFilesAccessIntent(ctx)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Grant file access") }
        }
        OutlinedButton(
            onClick = onPickFolder,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Choose log folder") }
    }
}

@Composable
private fun HintState(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun MemberRow(m: InstanceRosterManager.Member) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // Tap the row to reveal the step-by-step clone-resolution trace for this member (diagnostics).
    var traceOpen by remember(m.userId) { mutableStateOf(false) }
    // Each member is its OWN rounded card (subtle fill) so rows are visually separated
    // instead of blending into one block.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
      Column(
          Modifier.fillMaxWidth()
              .clickable { traceOpen = !traceOpen }
              .padding(horizontal = 10.dp, vertical = 7.dp)
      ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // Avatar: the VRChat pic when we have one, loaded through the session-authed
            // loader with DISK cache DISABLED — memory-only (evicts on leave), so nothing
            // builds up on disk. Initial-circle fallback while blank/loading.
            if (m.profilePicUrl.isNotBlank()) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(ctx)
                        .data(m.profilePicUrl)
                        .diskCachePolicy(coil.request.CachePolicy.DISABLED)
                        .crossfade(true)
                        .build(),
                    imageLoader = com.vrca.admin.VrchatImageLoader.get(ctx),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(34.dp).clip(CircleShape)
                )
            } else {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(34.dp)) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            m.displayName.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
            // Platform symbol — moved to the LEFT, right beside the profile picture.
            if (m.platform.isNotBlank()) {
                PlatformSymbol(m.platform)
            } else if (m.userId == null) {
                // Older name-only log format: no id to resolve a platform from.
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(12.dp)
                )
            }
            // Name + status line (dot coloured by status; text = status description, else label).
            Column(Modifier.weight(1f)) {
                Text(
                    m.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    // You = purple (pinned top), friends = yellow, everyone else default.
                    color = when {
                        m.isSelf -> androidx.compose.ui.graphics.Color(0xFFB388FF)
                        m.isFriend -> androidx.compose.ui.graphics.Color(0xFFFFD54F)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusText = m.statusDescription.ifBlank { rosterStatusLabel(m.status) }
                if (statusText.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(rosterStatusColor(m.status))
                        )
                        Text(
                            statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            // Action cluster (right) — non-self only. Exact order: friend request, clone,
            // block, mute. Friend request + clone are live; block + mute are visual for now.
            if (!m.isSelf) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FriendButton(m, ctx, scope)
                    CloneButton(m, ctx, scope)
                }
            }
        }
        // Expandable per-user resolution trace: every step the clone resolver walked for this
        // member's current avatar + the terminal outcome ("result: via …" / "result: 0 candidates").
        if (traceOpen) {
            Column(
                Modifier.fillMaxWidth().padding(start = 40.dp, end = 4.dp, top = 1.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                if (m.resolveTrace.isEmpty()) {
                    Text(
                        if (m.isSelf) "(you — not resolved)" else "resolving… (no trace yet)",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else m.resolveTrace.forEach { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (line.startsWith("result:")) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
      }
    }
}

/** Rounded-square tonal action button used in the roster row's right-side cluster
 *  (friend request / clone / block / mute). Uses Modifier.clickable (NOT
 *  Surface(onClick=…), which forces a 48dp min interactive size and would blow up
 *  the spacing) so the button stays exactly 34dp. */
@Composable
private fun RosterActionButton(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f),
        modifier = Modifier.size(26.dp)
    ) {
        Box(
            Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center
        ) { content() }
    }
}

/** Friend button — LIVE toggle. No user id → greyed. Already a friend → PersonRemove
 *  (tap unfriends). Not a friend → PersonAdd (tap sends a request, then shows sent/dimmed
 *  since a request isn't friendship yet). Unfriend flips optimistically (the friends cache
 *  catches up via the pipeline WS). */
@Composable
private fun FriendButton(
    m: InstanceRosterManager.Member,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    if (m.userId == null) {
        RosterActionButton(enabled = false, onClick = {}) {
            Icon(
                painterResource(com.vrca.R.drawable.ic_friend_add),
                contentDescription = "Cannot add",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(15.dp)
            )
        }
        return
    }
    var busy by remember(m.userId) { mutableStateOf(false) }
    var unfriended by remember(m.userId) { mutableStateOf(false) }
    var sent by remember(m.userId) { mutableStateOf(false) }
    val isFriend = m.isFriend && !unfriended
    val justSent = sent && !isFriend
    RosterActionButton(enabled = !busy && !justSent, onClick = {
        if (!busy) {
            busy = true
            scope.launch {
                val res = if (isFriend)
                    com.vrca.vrchat.VrchatAuthManager.unfriendUser(ctx, m.userId)
                else
                    com.vrca.vrchat.VrchatAuthManager.sendFriendRequest(ctx, m.userId)
                android.widget.Toast.makeText(
                    ctx,
                    if (res.ok) {
                        if (isFriend) "Unfriended ${m.displayName}"
                        else "Friend request sent to ${m.displayName}"
                    } else (res.error ?: "Couldn't complete that"),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                if (res.ok) { if (isFriend) unfriended = true else sent = true }
                busy = false
            }
        }
    }) {
        if (busy) {
            androidx.compose.material3.CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Icon(
                painterResource(
                    if (isFriend) com.vrca.R.drawable.ic_friend_remove
                    else com.vrca.R.drawable.ic_friend_add
                ),
                contentDescription = if (isFriend) "Unfriend" else "Send friend request",
                tint = if (justSent) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

/** Clone / "wear this avatar" button — LIVE (unchanged behaviour, restyled into the
 *  tonal square with the custom overlapping-people glyph). States:
 *   - no userId / dead   -> greyed/disabled,
 *   - avatarId null       -> spinner (still resolving),
 *   - avatarId ""         -> greyed (no cloneable match),
 *   - avatarId set        -> ready (tap to clone; a 403/404 reports + greys). */
@Composable
private fun CloneButton(
    m: InstanceRosterManager.Member,
    ctx: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val avaId = m.avatarId
    var deadLocally by remember(m.userId, m.avatarName, avaId) { mutableStateOf(false) }
    when {
        m.userId == null || deadLocally -> RosterActionButton(enabled = false, onClick = {}) {
            Icon(
                painterResource(com.vrca.R.drawable.ic_clone_people),
                contentDescription = "No cloneable avatar",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(15.dp)
            )
        }
        avaId == null -> RosterActionButton(enabled = false, onClick = {}) {
            androidx.compose.material3.CircularProgressIndicator(
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(15.dp)
            )
        }
        avaId.isBlank() -> RosterActionButton(enabled = false, onClick = {}) {
            Icon(
                painterResource(com.vrca.R.drawable.ic_clone_people),
                contentDescription = "No cloneable avatar found",
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(15.dp)
            )
        }
        else -> {
            var busy by remember(avaId) { mutableStateOf(false) }
            RosterActionButton(enabled = !busy, onClick = {
                if (!busy) {
                    busy = true
                    val name = m.avatarName
                    val fid = m.cloneFileId   // the EXACT catalog shard key this id resolved from
                    scope.launch {
                        val res = com.vrca.vrchat.VrchatAuthManager.selectAvatar(ctx, avaId)
                        android.widget.Toast.makeText(
                            ctx,
                            if (res.ok) "Cloned ${name ?: "avatar"} — shows on your next avatar reload"
                            else (res.error ?: "Couldn't wear this avatar"),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        // 403 = private/not accessible, 404 = deleted → not wearable by anyone but the
                        // owner. Report the EXACT resolved shard key (reliable; the live worn thumb may be
                        // the fallback by now) so the Worker culls it on quorum, and grey locally. A
                        // transient failure (429/5xx/network) is NOT reported — entry stays, tap retries.
                        if (!res.ok && (res.code == 403 || res.code == 404)) {
                            if (fid != null) com.vrca.vrchat.AvatarGlobalDb.report(ctx, fid, avaId, "dead")
                            deadLocally = true
                        }
                        busy = false
                    }
                }
            }) {
                if (busy) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Icon(
                        painterResource(com.vrca.R.drawable.ic_clone_people),
                        contentDescription = "Clone avatar",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }
    }
}

/** VRChat status → dot colour (active green / join-me blue / ask-me orange / busy red /
 *  offline·unknown grey). */
private fun rosterStatusColor(status: String): androidx.compose.ui.graphics.Color =
    when (status.lowercase()) {
        "active" -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
        "join me" -> androidx.compose.ui.graphics.Color(0xFF2196F3)
        "ask me" -> androidx.compose.ui.graphics.Color(0xFFFF9800)
        "busy" -> androidx.compose.ui.graphics.Color(0xFFE53935)
        else -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
    }

/** VRChat status → human label (shown only when there's no free-text status description). */
private fun rosterStatusLabel(status: String): String = when (status.lowercase()) {
    "active" -> "Online"
    "join me" -> "Join Me"
    "ask me" -> "Ask Me"
    "busy" -> "Busy"
    "offline" -> "Offline"
    else -> ""
}

/**
 * Circular BRAND-glyph platform badge — matches VRChat's own instance-card
 * platform symbols and the event-alert `PlatformSymbols`: Windows blue, Android/
 * Quest green, Apple light. Replaces the old text chip ("PC"/"Quest"/"iOS").
 */
@Composable
internal fun PlatformSymbol(platform: String) {
    val tint = when (platform) {
        "PC" -> androidx.compose.ui.graphics.Color(0xFF2196F3)   // Windows blue
        "Quest" -> androidx.compose.ui.graphics.Color(0xFF3DDC84) // Android brand green
        "iOS" -> androidx.compose.ui.graphics.Color(0xFFE0E0E0)   // Apple light grey
        else -> return
    }
    Surface(
        shape = CircleShape,
        color = tint.copy(alpha = 0.18f),
        modifier = Modifier.size(22.dp)
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (platform) {
                "PC" -> Icon(
                    androidx.compose.ui.res.painterResource(com.vrca.R.drawable.ic_platform_windows),
                    contentDescription = "PC", tint = tint, modifier = Modifier.size(12.dp)
                )
                "Quest" -> Icon(
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
