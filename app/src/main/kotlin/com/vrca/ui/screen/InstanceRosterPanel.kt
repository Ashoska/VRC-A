package com.vrca.ui.screen

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PeopleAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
                        ui.members.forEach { m -> MemberRow(m) }
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
    Column(Modifier.fillMaxWidth()) {
    Row(
        Modifier.fillMaxWidth().clickable { traceOpen = !traceOpen },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Avatar: the VRChat pic when we have one, loaded through the session-authed
        // loader with DISK cache DISABLED — it lives only in Coil's bounded memory
        // cache (temporary, evicts when the user leaves / on memory pressure), so
        // nothing builds up on disk. Initial-circle fallback while blank/loading.
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
                modifier = Modifier.size(30.dp).clip(CircleShape)
            )
        } else {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface, modifier = Modifier.size(30.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        m.displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
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
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
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
        // Clone/wear button — shown for EVERY non-self member (no gaps). The exact
        // avatar id is resolved by the worn image FILE ID (name-optional, so even an
        // impostor'd player with no log avatar name gets one). States:
        //  - no userId  -> greyed/disabled (unresolvable),
        //  - avatarId null -> spinner (still resolving),
        //  - avatarId ""   -> greyed (no cloneable match anywhere),
        //  - avatarId set  -> ready (tap to clone). A failed clone (avatar now private/
        //    deleted) confirms + reports it, then greys out.
        if (!m.isSelf) {
            val avaId = m.avatarId
            var deadLocally by remember(m.userId, m.avatarName, avaId) { mutableStateOf(false) }
            when {
                m.userId == null || deadLocally -> IconButton(
                    onClick = {}, enabled = false, modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.PeopleAlt,
                        contentDescription = "No cloneable avatar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(17.dp)
                    )
                }
                avaId == null -> Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                // Greyed. Two kinds: a LOADING give-up (their avatar hadn't loaded when we went quiet —
                // TAP-REPROBE-able, shows a refresh glyph) vs a DEFINITIVE noMatch/dead (not retriable,
                // shows the muted clone glyph). The tap does ONE cheap /users probe and only re-searches
                // the DBs if new info actually loaded — and is rate-limited to once/min per member.
                avaId.isBlank() && m.userId != null &&
                    com.vrca.vrchat.InstanceRosterManager.canRetryClone(m.userId) -> {
                    var probing by remember(m.userId, m.avatarName) { mutableStateOf(false) }
                    val uid = m.userId
                    IconButton(
                        onClick = {
                            if (probing) return@IconButton
                            probing = true
                            scope.launch {
                                val r = com.vrca.vrchat.InstanceRosterManager.retryClone(ctx, uid)
                                val msg = when (r) {
                                    com.vrca.vrchat.InstanceRosterManager.RetryResult.REARMED -> "Checking again…"
                                    com.vrca.vrchat.InstanceRosterManager.RetryResult.NOTHING_NEW -> "Nothing new loaded yet"
                                    com.vrca.vrchat.InstanceRosterManager.RetryResult.RATE_LIMITED -> "Try again in a moment"
                                    com.vrca.vrchat.InstanceRosterManager.RetryResult.NOT_RETRIABLE -> "No cloneable avatar"
                                }
                                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                                probing = false
                            }
                        },
                        enabled = !probing,
                        modifier = Modifier.size(28.dp)
                    ) {
                        if (probing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.Refresh,
                                contentDescription = "Retry clone lookup",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                avaId.isBlank() -> IconButton(
                    onClick = {}, enabled = false, modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Filled.PeopleAlt,
                        contentDescription = "No cloneable avatar found",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(17.dp)
                    )
                }
                else -> {
                    var busy by remember(avaId) { mutableStateOf(false) }
                    IconButton(
                        onClick = {
                            if (busy) return@IconButton
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
                                // VRChat DEFINITIVELY rejected the select: 404 = deleted, 403 = private /
                                // not accessible. Either way it's not wearable by anyone but the owner, so
                                // it must not keep showing as clonable. Report the EXACT resolved shard key
                                // (m.cloneFileId — reliable; the target's live worn thumbnail may be the
                                // fallback by now, which is why we don't re-derive it here) so the Worker
                                // culls it on quorum, and grey the button locally right away. A transient
                                // failure (429/5xx/network) is NOT reported — the entry stays, tap retries.
                                if (!res.ok && (res.code == 403 || res.code == 404)) {
                                    if (fid != null) com.vrca.vrchat.AvatarGlobalDb.report(ctx, fid, avaId, "dead")
                                    deadLocally = true
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.size(28.dp)
                    ) {
                        if (busy) {
                            androidx.compose.material3.CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            Icon(
                                Icons.Filled.PeopleAlt,
                                contentDescription = "Clone avatar",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
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
