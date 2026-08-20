package com.vrca.admin

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit

/* =========================================================
   DASHBOARD — a unified activity feed (moderation, ban-evasion,
   announcements, VRChat platform status) plus the at-a-glance
   stat grid. Replaces the old redundant "Live overview" hero.

   READ COST: the feed is persisted on the admin's device and
   only ever fetches events NEWER than the last cached one. A
   fresh in-memory/disk cache (< TTL) costs ZERO reads; an
   incremental refresh costs ~1 read per source when nothing is
   new (Firestore's per-query minimum), scaling only with the
   number of genuinely new events. The full ~33-doc read happens
   exactly once (first run), then never again.
   ========================================================= */

/** Serializable event kind → icon + tone are derived at render time so the
 *  feed can be persisted to disk (an ImageVector/Color can't be). */
internal enum class ActivityKind {
    BAN, UNBAN, WARN, REMOVE_WARN, EVASION,
    ANNOUNCEMENT, ANNOUNCEMENT_DRAFT,
    VRC_CRITICAL, VRC_MAJOR, VRC_MINOR, VRC_INFO,
    OTHER
}

private fun ActivityKind.icon(): ImageVector = when (this) {
    ActivityKind.BAN, ActivityKind.UNBAN -> Icons.Filled.Gavel
    ActivityKind.WARN, ActivityKind.REMOVE_WARN -> Icons.Filled.Warning
    ActivityKind.EVASION -> Icons.Filled.Shield
    ActivityKind.ANNOUNCEMENT, ActivityKind.ANNOUNCEMENT_DRAFT -> Icons.Filled.Campaign
    ActivityKind.VRC_CRITICAL, ActivityKind.VRC_MAJOR,
    ActivityKind.VRC_MINOR, ActivityKind.VRC_INFO -> Icons.Filled.Public
    ActivityKind.OTHER -> Icons.Filled.History
}

private fun ActivityKind.tone(): AdminTone = when (this) {
    ActivityKind.BAN, ActivityKind.EVASION,
    ActivityKind.VRC_CRITICAL, ActivityKind.VRC_MAJOR -> AdminTone.Error
    ActivityKind.UNBAN -> AdminTone.Success
    ActivityKind.WARN, ActivityKind.VRC_MINOR -> AdminTone.Warn
    ActivityKind.ANNOUNCEMENT, ActivityKind.VRC_INFO -> AdminTone.Info
    ActivityKind.REMOVE_WARN, ActivityKind.ANNOUNCEMENT_DRAFT, ActivityKind.OTHER -> AdminTone.Neutral
}

/** One row in the dashboard activity timeline. */
internal data class ActivityEvent(
    val id: String,
    val kind: ActivityKind,
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

/** Reuse window: within this, opening the tab costs ZERO Firestore reads. */
private const val FEED_CACHE_TTL_MS = 60_000L
private const val FEED_PREFS = "vrca_admin_dashboard"

/**
 * Process-level + on-disk cache for the activity feed. Survives tab
 * dispose/recreate (process cache) AND app restarts (SharedPreferences), so the
 * feed renders instantly with no reads and only ever fetches new events.
 */
private object DashboardFeedCache {
    @Volatile var events: List<ActivityEvent> = emptyList()
    @Volatile var newestModMs: Long = 0L   // cursor: newest moderationEvents.createdAt seen
    @Volatile var newestAnnMs: Long = 0L   // cursor: newest announcements.createdAt seen
    @Volatile var fetchedAtMs: Long = 0L
    @Volatile private var loaded = false

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val p = ctx.getSharedPreferences(FEED_PREFS, Context.MODE_PRIVATE)
            newestModMs = p.getLong("newest_mod_ms", 0L)
            newestAnnMs = p.getLong("newest_ann_ms", 0L)
            fetchedAtMs = p.getLong("fetched_at_ms", 0L)
            p.getString("events_json", null)?.let { json ->
                val arr = JSONArray(json)
                val list = ArrayList<ActivityEvent>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ActivityEvent(
                            id = o.getString("id"),
                            kind = runCatching { ActivityKind.valueOf(o.getString("kind")) }
                                .getOrDefault(ActivityKind.OTHER),
                            title = o.optString("title", ""),
                            subtitle = o.optString("subtitle", ""),
                            timeMs = o.optLong("timeMs", 0L)
                        )
                    )
                }
                events = list
            }
        }
    }

    fun save(ctx: Context) {
        runCatching {
            val arr = JSONArray()
            events.forEach { e ->
                arr.put(JSONObject().apply {
                    put("id", e.id)
                    put("kind", e.kind.name)
                    put("title", e.title)
                    put("subtitle", e.subtitle)
                    put("timeMs", e.timeMs)
                })
            }
            ctx.getSharedPreferences(FEED_PREFS, Context.MODE_PRIVATE).edit()
                .putString("events_json", arr.toString())
                .putLong("newest_mod_ms", newestModMs)
                .putLong("newest_ann_ms", newestAnnMs)
                .putLong("fetched_at_ms", fetchedAtMs)
                .apply()
        }
    }
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
    val ctx = LocalContext.current
    remember { DashboardFeedCache.ensureLoaded(ctx); true }

    val totalUsers  = totalUsersCount
    // Online is liveness-driven, identical to the directory's per-row check —
    // fresh `lastActiveAt` (minus a newer clean-shutdown `offlineAt`) — NOT the
    // unreliable `isOnlineInApp` flag, which pre-app-scoped-VM builds wrote false
    // on every Activity destruction (the "online user shows offline" bug). Using
    // the same predicate keeps the counter and the directory rows consistent.
    val onlineCount = users.count { isUserOnline(it) }
    val bannedCount = bannedUsersCount
    val warnedCount = warnedUsersCount

    var events by remember { mutableStateOf(DashboardFeedCache.events) }
    var feedLoading by remember { mutableStateOf(DashboardFeedCache.events.isEmpty()) }
    var feedTick by remember { mutableIntStateOf(0) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(15_000L) }
    }

    // Incremental feed fetch — only events newer than the cached cursors.
    LaunchedEffect(feedTick) {
        val now = System.currentTimeMillis()
        val cacheFresh = now - DashboardFeedCache.fetchedAtMs < FEED_CACHE_TTL_MS
        // feedTick == 0 is tab entry: reuse a fresh cache with ZERO reads.
        // A manual refresh bumps feedTick (> 0) and always does an incremental fetch.
        if (feedTick == 0 && cacheFresh && DashboardFeedCache.events.isNotEmpty()) {
            events = DashboardFeedCache.events
            feedLoading = false
            return@LaunchedEffect
        }
        feedLoading = true
        val fetched = mutableListOf<ActivityEvent>()
        var maxMod = DashboardFeedCache.newestModMs
        var maxAnn = DashboardFeedCache.newestAnnMs

        // 1) Moderation events — ONLY newer than the cursor (≤1 read when none).
        runCatching {
            var q: Query = db.collection("moderationEvents")
                .orderBy("createdAt", Query.Direction.DESCENDING)
            if (DashboardFeedCache.newestModMs > 0L)
                q = q.whereGreaterThan("createdAt", Timestamp(Date(DashboardFeedCache.newestModMs)))
            val snap = q.limit(25).get(Source.SERVER).await()
            snap.documents.forEach { d ->
                fun s(k: String) = (d.getString(k) ?: "").trim()
                val action = s("action")
                val who = s("newDisplayName").ifBlank { s("targetUid") }.ifBlank { s("targetDeviceHash").take(12) }
                val reason = s("reason")
                val method = s("method")
                val tMs = d.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                if (tMs > maxMod) maxMod = tMs
                val ev = when (action) {
                    "ban" -> ActivityEvent(d.id, ActivityKind.BAN,
                        "Banned${if (who.isNotBlank()) " · $who" else ""}",
                        reason.ifBlank { "no reason given" }, tMs)
                    "unban" -> ActivityEvent(d.id, ActivityKind.UNBAN,
                        "Unbanned${if (who.isNotBlank()) " · $who" else ""}", "", tMs)
                    "warn" -> ActivityEvent(d.id, ActivityKind.WARN,
                        "Warned${if (who.isNotBlank()) " · $who" else ""}",
                        reason.ifBlank { "no reason given" }, tMs)
                    "remove_warn" -> ActivityEvent(d.id, ActivityKind.REMOVE_WARN,
                        "Warning removed${if (who.isNotBlank()) " · $who" else ""}", "", tMs)
                    "ban_evasion_detected" -> ActivityEvent(d.id, ActivityKind.EVASION,
                        "Ban evasion detected",
                        buildString {
                            if (method.isNotBlank()) append(method)
                            if (who.isNotBlank()) { if (isNotEmpty()) append(" · "); append(who) }
                        }.ifBlank { "new identifier on a banned record" }, tMs)
                    else -> ActivityEvent(d.id, ActivityKind.OTHER,
                        action.replace("_", " ").replaceFirstChar { it.uppercase() }
                            .ifBlank { "Event" } + (if (who.isNotBlank()) " · $who" else ""),
                        reason, tMs)
                }
                fetched.add(ev)
            }
        }.onFailure { /* best-effort source */ }

        // 2) Announcements — ONLY newer than the cursor.
        runCatching {
            var q: Query = db.collection("announcements")
                .orderBy("createdAt", Query.Direction.DESCENDING)
            if (DashboardFeedCache.newestAnnMs > 0L)
                q = q.whereGreaterThan("createdAt", Timestamp(Date(DashboardFeedCache.newestAnnMs)))
            val snap = q.limit(8).get(Source.SERVER).await()
            snap.documents.forEach { d ->
                val title = (d.getString("title") ?: "").trim()
                val active = d.getBoolean("active") ?: true
                val tMs = d.getTimestamp("createdAt")?.toDate()?.time ?: 0L
                if (tMs > maxAnn) maxAnn = tMs
                fetched.add(ActivityEvent(
                    id = "ann_${d.id}",
                    kind = if (active) ActivityKind.ANNOUNCEMENT else ActivityKind.ANNOUNCEMENT_DRAFT,
                    title = "Announcement${if (!active) " (draft)" else ""}",
                    subtitle = title.ifBlank { "(no title)" },
                    timeMs = tMs
                ))
            }
        }.onFailure { /* best-effort source */ }

        // 3) VRChat platform status — HTTP, NOT Firestore (free).
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
                                val kind = when (impact) {
                                    "critical" -> ActivityKind.VRC_CRITICAL
                                    "major" -> ActivityKind.VRC_MAJOR
                                    "minor" -> ActivityKind.VRC_MINOR
                                    else -> ActivityKind.VRC_INFO
                                }
                                fetched.add(ActivityEvent(
                                    id = "vrc_${inc.optString("id", name)}",
                                    kind = kind,
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

        // Merge new events into the cached list (new wins on id), sort, cap 40.
        val byId = LinkedHashMap<String, ActivityEvent>()
        DashboardFeedCache.events.forEach { byId[it.id] = it }
        fetched.forEach { byId[it.id] = it }
        val sorted = byId.values.sortedByDescending { it.timeMs }.take(40)

        events = sorted
        DashboardFeedCache.events = sorted
        DashboardFeedCache.newestModMs = maxMod
        DashboardFeedCache.newestAnnMs = maxAnn
        DashboardFeedCache.fetchedAtMs = System.currentTimeMillis()
        DashboardFeedCache.save(ctx)
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

        // (Avatar-catalog health + the bot sweep controls now live in the "Bots" tab.)

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
    val tc = toneColors(ev.kind.tone())
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(color = tc.accent.copy(alpha = 0.16f), shape = CircleShape) {
            Icon(
                ev.kind.icon(), contentDescription = null,
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
