package com.vrca.admin

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/* =========================================================
   DASHBOARD — a unified live activity feed (moderation,
   ban-evasion, announcements, VRChat platform status) plus
   the at-a-glance stat grid. Replaces the old redundant
   "Live overview" count hero.
   ========================================================= */

/** One row in the dashboard activity timeline. */
internal data class ActivityEvent(
    val id: String,
    val icon: ImageVector,
    val tone: AdminTone,
    val title: String,
    val subtitle: String,
    val timeMs: Long
)

private val dashboardHttp by lazy {
    OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
}

/** Reuse window for the activity feed so re-entering the tab doesn't re-read. */
private const val FEED_CACHE_TTL_MS = 60_000L

/** Process-level cache for the activity feed (survives tab dispose/recreate). */
private object DashboardFeedCache {
    @Volatile var events: List<ActivityEvent> = emptyList()
    @Volatile var fetchedAtMs: Long = 0L
}

@Composable
internal fun DashboardTab(
    db: FirebaseFirestore,
    users: List<UserRow>,
    usersLoading: Boolean,
    totalUsersCount: Int,
    warnedUsersCount: Int,
    bannedUsersCount: Int,
    onRefresh: () -> Unit,
    setError: (String?) -> Unit
) {
    val totalUsers  = totalUsersCount
    // Dashboard counter uses the raw isOnlineInApp flag (no staleness window)
    // so online users show immediately when the admin opens the app, before
    // their heartbeats arrive. The admin-side staleness sweep cleans up dead users.
    val onlineCount = users.count { it.isOnlineInApp }
    val bannedCount = bannedUsersCount
    val warnedCount = warnedUsersCount

    // Seed from the process-level cache so re-entering the Dashboard tab doesn't
    // re-fetch — switching tabs disposes/recreates this composable, and without
    // the cache every visit would cost ~33 Firestore reads. The cache is reused
    // for FEED_CACHE_TTL_MS; the refresh button (feedTick) always forces a fetch.
    var events by remember { mutableStateOf(DashboardFeedCache.events) }
    var feedLoading by remember { mutableStateOf(DashboardFeedCache.events.isEmpty()) }
    var feedTick by remember { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(15_000L) }
    }

    // Aggregate the activity feed from several sources, then merge + sort.
    LaunchedEffect(feedTick) {
        val cacheFresh = System.currentTimeMillis() - DashboardFeedCache.fetchedAtMs < FEED_CACHE_TTL_MS
        // feedTick == 0 is the initial composition (tab entry). Reuse a fresh
        // cache instead of re-reading; a manual refresh bumps feedTick (> 0).
        if (feedTick == 0 && cacheFresh && DashboardFeedCache.events.isNotEmpty()) {
            events = DashboardFeedCache.events
            feedLoading = false
            return@LaunchedEffect
        }
        feedLoading = true
        val merged = mutableListOf<ActivityEvent>()

        // 1) Moderation events (bans / warns / unbans / ban-evasion captures).
        runCatching {
            val snap = db.collection("moderationEvents")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(25)
                .get(Source.SERVER)
                .await()
            snap.documents.forEach { d ->
                fun s(k: String) = (d.getString(k) ?: "").trim()
                val action = s("action")
                val who = s("newDisplayName").ifBlank { s("targetUid") }.ifBlank { s("targetDeviceHash").take(12) }
                val reason = s("reason")
                val method = s("method")
                val tMs = d.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                val ev = when (action) {
                    "ban" -> ActivityEvent(d.id, Icons.Filled.Gavel, AdminTone.Error,
                        "Banned${if (who.isNotBlank()) " · $who" else ""}",
                        reason.ifBlank { "no reason given" }, tMs)
                    "unban" -> ActivityEvent(d.id, Icons.Filled.Gavel, AdminTone.Success,
                        "Unbanned${if (who.isNotBlank()) " · $who" else ""}", "", tMs)
                    "warn" -> ActivityEvent(d.id, Icons.Filled.Warning, AdminTone.Warn,
                        "Warned${if (who.isNotBlank()) " · $who" else ""}",
                        reason.ifBlank { "no reason given" }, tMs)
                    "remove_warn" -> ActivityEvent(d.id, Icons.Filled.Warning, AdminTone.Neutral,
                        "Warning removed${if (who.isNotBlank()) " · $who" else ""}", "", tMs)
                    "ban_evasion_detected" -> ActivityEvent(d.id, Icons.Filled.Shield, AdminTone.Error,
                        "Ban evasion detected",
                        buildString {
                            if (method.isNotBlank()) append(method)
                            if (who.isNotBlank()) { if (isNotEmpty()) append(" · "); append(who) }
                        }.ifBlank { "new identifier on a banned record" }, tMs)
                    else -> ActivityEvent(d.id, Icons.Filled.History, AdminTone.Neutral,
                        action.replace("_", " ").replaceFirstChar { it.uppercase() }
                            .ifBlank { "Event" } + (if (who.isNotBlank()) " · $who" else ""),
                        reason, tMs)
                }
                merged.add(ev)
            }
        }.onFailure { /* best-effort source */ }

        // 2) Announcements posted.
        runCatching {
            val snap = db.collection("announcements")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(8)
                .get(Source.SERVER)
                .await()
            snap.documents.forEach { d ->
                val title = (d.getString("title") ?: "").trim()
                val active = d.getBoolean("active") ?: true
                val tMs = d.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                merged.add(ActivityEvent(
                    id = "ann_${d.id}",
                    icon = Icons.Filled.Campaign,
                    tone = AdminTone.Info,
                    title = "Announcement${if (!active) " (draft)" else ""}",
                    subtitle = title.ifBlank { "(no title)" },
                    timeMs = tMs
                ))
            }
        }.onFailure { /* best-effort source */ }

        // 3) VRChat platform status (incidents + overall indicator).
        runCatching {
            withContext(Dispatchers.IO) {
                val req = Request.Builder()
                    .url("https://status.vrchat.com/api/v2/summary.json")
                    .header("Cache-Control", "no-cache")
                    .build()
                dashboardHttp.newCall(req).execute().use { resp ->
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val root = JSONObject(body)
                        val incidents = root.optJSONArray("incidents")
                        if (incidents != null) {
                            for (i in 0 until minOf(incidents.length(), 5)) {
                                val inc = incidents.getJSONObject(i)
                                val name = inc.optString("name").trim()
                                val impact = inc.optString("impact", "none")
                                val status = inc.optString("status", "").trim()
                                val updated = inc.optString("updated_at").ifBlank { inc.optString("created_at") }
                                val tMs = parseIsoMs(updated)
                                val tone = when (impact) {
                                    "critical" -> AdminTone.Error
                                    "major" -> AdminTone.Error
                                    "minor" -> AdminTone.Warn
                                    else -> AdminTone.Info
                                }
                                merged.add(ActivityEvent(
                                    id = "vrc_${inc.optString("id", name)}",
                                    icon = Icons.Filled.Public,
                                    tone = tone,
                                    title = "VRChat: ${name.ifBlank { "status update" }}",
                                    subtitle = buildString {
                                        append(impact.replaceFirstChar { it.uppercase() })
                                        if (status.isNotBlank()) append(" · ${status.replace("_", " ")}")
                                    },
                                    timeMs = tMs
                                ))
                            }
                        }
                    }
                }
            }
        }.onFailure { /* best-effort source */ }

        val sorted = merged.sortedByDescending { it.timeMs }.take(40)
        events = sorted
        DashboardFeedCache.events = sorted
        DashboardFeedCache.fetchedAtMs = System.currentTimeMillis()
        feedLoading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Stat grid (2x2) — the at-a-glance numbers.
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Users", value = totalUsers.toString(),
                        icon = Icons.Filled.Group, tone = AdminTone.Neutral
                    )
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Online", value = onlineCount.toString(),
                        icon = Icons.Filled.Bolt,
                        tone = if (onlineCount > 0) AdminTone.Primary else AdminTone.Neutral
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Warned", value = warnedCount.toString(),
                        icon = Icons.Filled.Warning,
                        tone = if (warnedCount > 0) AdminTone.Warn else AdminTone.Neutral
                    )
                    AdminStatTile(
                        modifier = Modifier.weight(1f),
                        label = "Banned", value = bannedCount.toString(),
                        icon = Icons.Filled.Block,
                        tone = if (bannedCount > 0) AdminTone.Error else AdminTone.Neutral
                    )
                }
            }
        }

        // Activity feed.
        item {
            AdminSectionCard(
                title = "Recent activity",
                icon = Icons.Filled.History,
                tone = AdminTone.Primary,
                trailing = {
                    if (feedLoading || usersLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { onRefresh(); feedTick++ }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                        }
                    }
                }
            ) {
                if (events.isEmpty() && !feedLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Filled.SportsEsports, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp))
                        Text("Nothing's happened yet. New moderation actions, " +
                            "announcements and VRChat status alerts will show up here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    events.forEach { ev -> ActivityRow(ev, nowMs) }
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ActivityRow(ev: ActivityEvent, nowMs: Long) {
    val tc = toneColors(ev.tone)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = tc.accent.copy(alpha = 0.16f), shape = CircleShape) {
            Icon(
                ev.icon, contentDescription = null,
                tint = tc.accent,
                modifier = Modifier.padding(7.dp).size(18.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                ev.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (ev.subtitle.isNotBlank()) {
                Text(
                    ev.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            relativeShort(ev.timeMs, nowMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Parses an ISO-8601 timestamp (e.g. "2024-05-31T12:00:00.000Z") to epoch ms. */
private fun parseIsoMs(iso: String): Long {
    if (iso.isBlank()) return 0L
    return runCatching {
        // Handles both with/without millis and the trailing Z.
        val fmt = if (iso.contains('.'))
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX" else "yyyy-MM-dd'T'HH:mm:ssXXX"
        java.text.SimpleDateFormat(fmt, java.util.Locale.US).parse(iso)?.time ?: 0L
    }.getOrDefault(0L)
}

/** Compact relative time: 12s / 5m / 3h / 2d. */
private fun relativeShort(timeMs: Long, nowMs: Long): String {
    if (timeMs <= 0L) return ""
    val diff = nowMs - timeMs
    if (diff < 0L) return "now"
    val s = diff / 1000L
    if (s < 60L) return "${s}s"
    val m = s / 60L
    if (m < 60L) return "${m}m"
    val h = m / 60L
    if (h < 24L) return "${h}h"
    return "${h / 24L}d"
}
