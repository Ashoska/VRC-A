// app/src/main/kotlin/com/vrca/AdminScreen.kt
package com.vrca.admin

import android.content.Context
import com.vrca.BuildConfig
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.LinearProgressIndicator
import java.io.File
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Check

internal fun fmtRelativeTime(nowMs: Long, thenMs: Long): String {
    val delta = kotlin.math.abs(nowMs - thenMs)
    val sec = delta / 1000L
    return when {
        sec < 5 -> "just now"
        sec < 60 -> "${sec}s ago"
        sec < 3600 -> "${sec / 60}m ago"
        sec < 86400 -> "${sec / 3600}h ago"
        else -> "${sec / 86400}d ago"
    }
}

/**
 * Owner-only Admin screen.
 *
 * Canonical users doc is:
 * - users/{docId}  (docId usually == deviceHash)
 *
 * Mapping doc is:
 * - usersById/{uid} -> { deviceHash, authUid, appId, adminBuild, updatedAt }
 */
// Once-per-COLD-LAUNCH guard for the targeted directory liveness refresh. A
// top-level val is process-lifetime: it resets to false on a fresh process (cold
// launch) but stays true across Activity recreation / tab switches within the
// same session, so the refresh fires exactly once per app open. Set true on ANY
// server pull (full fetch OR the targeted cached-user refresh) so we never read
// twice in one session.
private val sessionDirectoryRefreshed = java.util.concurrent.atomic.AtomicBoolean(false)

@Composable
fun AdminScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val db = remember { FirebaseFirestore.getInstance() }
    val auth = remember { FirebaseAuth.getInstance() }

    var globalLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun setErr(msg: String?) {
        error = msg?.trim()?.takeIf { it.isNotBlank() }?.take(4000)
    }

    // Hard block: should never be reachable on public build.
    if (!BuildConfig.IS_ADMIN_BUILD) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                ErrorCard("This page is only available in the Admin build.")
            }
        }
        return
    }

    val deviceHash = remember { readDeviceHash(ctx) }

    // UID: read cached first, then ensure anon auth
    var myUid by remember { mutableStateOf(readCachedUid(ctx)) }

    LaunchedEffect(Unit) {
        if (myUid.isNotBlank()) return@LaunchedEffect
        runCatching {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val uid = auth.currentUser?.uid.orEmpty()
            if (uid.isNotBlank()) {
                writeCachedUid(ctx, uid)
                myUid = uid
            }
        }.onFailure { e ->
            setErr(e.message ?: "Auth failed while trying to get UID")
        }
    }

    // Owner gate (config/app.ownerUid)
    var ownerChecked by remember { mutableStateOf(false) }
    var ownerUid by remember { mutableStateOf("") }
    var isOwner by remember { mutableStateOf(false) }

    suspend fun refreshOwnerGate() {
        ownerChecked = false
        ownerUid = ""
        isOwner = false
        setErr(null)

        runCatching {
            val snap = db.collection("config").document("app").get().await()
            ownerUid = snap.getString("ownerUid") ?: ""
            isOwner = ownerUid.isNotBlank() && myUid.isNotBlank() && ownerUid == myUid
            ownerChecked = true
        }.onFailure { e ->
            setErr(e.message ?: "Failed to load app config")
            ownerChecked = true
        }
    }

    LaunchedEffect(myUid) { refreshOwnerGate() }

    // Gate loading screen
    if (!ownerChecked) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator()
                    Text("Checking access...")
                }
                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // Access denied
    if (!isOwner) {
        Surface {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Admin", style = MaterialTheme.typography.titleLarge)

                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Access denied", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "This account is not the owner.\n\nUID: ${myUid.ifBlank { "(not available yet)" }}",
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("IDs", style = MaterialTheme.typography.titleSmall)
                        Text("deviceHash=${deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("uid=${myUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("ownerUid=${ownerUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        myUid = readCachedUid(ctx).ifBlank { myUid }
                        scope.launch { refreshOwnerGate() }
                        setErr(null)
                    }) { Text("Re-check") }

                    if (error != null) OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }

                if (error != null) ErrorCard(error!!)
            }
        }
        return
    }

    // ==========================================
    // MAIN UI
    // ==========================================
    val tabs = remember { listOf("Dashboard", "Users", "Mod", "Announce", "Releases", "Config", "Log") }
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }

    // ModerationTarget is NOT saveable
    var moderationTarget by remember { mutableStateOf<ModerationTarget?>(null) }

    // Compact IDs drawer
    var idsExpanded by rememberSaveable { mutableStateOf(false) }

    // ── Shared user list (drives both Dashboard stats and Users directory) ──
    // Phase 3 read model: the directory is a ONE-SHOT server fetch, NOT a live
    // snapshot listener. A collection listener re-reads on every user-doc change
    // across the whole base — the dominant admin-side Firestore cost. Instead we
    // fetch once on tab entry (and on manual refresh / page-size bump via
    // `refreshTick`) and let the admin pull-to-refresh for fresh data. Aggregate
    // stats (below) and the selected-user 10s detail poll cover the live needs.
    var sharedUsers by remember { mutableStateOf<List<UserRow>>(emptyList()) }
    // Default fetch ceiling raised from 500 → 2000 so the whole base loads (no
    // "only the 500 most-recently-active show" cap); +500 can still raise it.
    var sharedLiveLimit by rememberSaveable { mutableIntStateOf(2000) }
    var sharedUsersLoading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableIntStateOf(0) }
    val needsUsers = tabIndex == 0 || tabIndex == 1

    // Stats from count() aggregations — much cheaper than reading all docs.
    var totalUsersCount by remember { mutableIntStateOf(0) }
    var warnedUsersCount by remember { mutableIntStateOf(0) }
    var bannedUsersCount by remember { mutableIntStateOf(0) }

    // The (limit:refreshTick) of the last actual server pull. Initialised to the
    // first composition's key so plain tab entry/re-entry does NOT trigger a
    // read — only a manual refresh / +500 (key change) or an empty cache
    // (first-ever load) does. Opening the directory renders the cached list with
    // ZERO Firestore reads.
    var lastFetchedKey by rememberSaveable { mutableStateOf("2000:0") }

    LaunchedEffect(needsUsers, sharedLiveLimit, refreshTick) {
        if (!needsUsers) return@LaunchedEffect
        val key = "$sharedLiveLimit:$refreshTick"

        // 1) Instant render from the local cache (0 reads).
        if (sharedUsers.isEmpty()) {
            val cached = UsersDirectoryCache.load(ctx)
            if (cached.isNotEmpty()) {
                sharedUsers = cached
                sharedUsersLoading = false
            }
        }

        // 2) Hit the server ONLY when there's nothing to show or the admin
        //    explicitly refreshed / paged (+500). Plain tab re-entry = no read.
        val explicitRefresh = key != lastFetchedKey
        if (sharedUsers.isEmpty() || explicitRefresh) {
            sharedUsersLoading = sharedUsers.isEmpty()
            try {
                val snap = db.collection("users")
                    .limit(sharedLiveLimit.toLong())
                    .get(Source.SERVER)
                    .await()
                val rows = snap.documents
                    .filter { it.id != deviceHash }
                    .map { parseUserRow(it) }
                    .sortedByDescending {
                        (it.lastActiveAt ?: it.lastSeenAt)?.toDate()?.time ?: 0L
                    }
                sharedUsers = rows
                UsersDirectoryCache.save(ctx, rows)
                lastFetchedKey = key
                // A full fetch already freshened everything — don't also run the
                // targeted refresh this session.
                sessionDirectoryRefreshed.set(true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("AdminScreen", "Users fetch failed", e)
                setErr(e.message ?: "Users load failed")
            }
        } else if (sessionDirectoryRefreshed.compareAndSet(false, true)) {
            // ONCE per cold launch, when rendering a populated cache without a full
            // fetch: re-read ONLY the cached users' docs to freshen their liveness
            // so the online/offline dots are correct at session start. This is
            // bounded by the cache size and queried by document id in chunks of 10
            // (whereIn limit) — it NEVER scans the whole collection, so it can't
            // "overload" reads or pull uncached/new users. Reads ≈ cached-user
            // count, once per app open; plain tab re-entry stays at 0 reads.
            try {
                val ids = sharedUsers.map { it.docId }.filter { it.isNotBlank() }
                val freshById = HashMap<String, UserRow>(ids.size)
                ids.chunked(10).forEach { chunk ->
                    val snap = db.collection("users")
                        .whereIn(FieldPath.documentId(), chunk)
                        .get(Source.SERVER)
                        .await()
                    snap.documents.forEach { d -> freshById[d.id] = parseUserRow(d) }
                }
                if (freshById.isNotEmpty()) {
                    val merged = sharedUsers
                        .map { freshById[it.docId] ?: it }
                        .sortedByDescending {
                            (it.lastActiveAt ?: it.lastSeenAt)?.toDate()?.time ?: 0L
                        }
                    sharedUsers = merged
                    UsersDirectoryCache.save(ctx, merged)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("AdminScreen", "Session liveness refresh failed", e)
            }
        }
        sharedUsersLoading = false
    }

    // Aggregate stats: refreshed ONCE on tab entry and on manual refresh (keyed
    // on refreshTick) — NOT polled on a timer. count() costs 1 read per 1000 docs
    // counted; a 60s poll just burned reads for no benefit at this cadence.
    LaunchedEffect(needsUsers, refreshTick) {
        if (!needsUsers) return@LaunchedEffect
        try {
            val totalSnap = db.collection("users")
                .whereEqualTo("adminBuild", false)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()
            totalUsersCount = totalSnap.count.toInt()

            val warnedSnap = db.collection("users")
                .whereEqualTo("warned", true)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()
            warnedUsersCount = warnedSnap.count.toInt()

            val bannedSnap = db.collection("users")
                .whereEqualTo("banned", true)
                .count()
                .get(com.google.firebase.firestore.AggregateSource.SERVER)
                .await()
            bannedUsersCount = bannedSnap.count.toInt()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Throwable) { /* best-effort */ }
    }

    // Admin browsing heartbeat + force-kill staleness sweep now live in AdminRuntime
    // (app/process lifetime) so they keep running when the admin app is backgrounded
    // and its Activity is destroyed — "works as if on screen". The Compose layer only
    // registers intent (which tab is active) and pushes the latest user list for the
    // sweep. AdminRuntime is started once below.
    LaunchedEffect(Unit) { AdminRuntime.start(deviceHash) }
    LaunchedEffect(needsUsers) { AdminRuntime.setBrowsing(needsUsers) }
    LaunchedEffect(sharedUsers) {
        AdminRuntime.updateSweepData(
            sharedUsers.map {
                AdminRuntime.SweepUser(
                    docId = it.docId,
                    isOnlineInApp = it.isOnlineInApp,
                    lastActiveMs = (it.lastActiveAt ?: it.lastSeenAt)?.toDate()?.time ?: 0L
                )
            }
        )
    }

    Surface {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header (clean)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Admin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Owner build - VRC-A Admin",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = { idsExpanded = !idsExpanded }) {
                        Icon(Icons.Filled.Info, contentDescription = "IDs")
                    }
                    IconButton(onClick = {
                        scope.launch {
                            myUid = readCachedUid(ctx).ifBlank { myUid }
                            refreshOwnerGate()
                        }
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh gate")
                    }
                }
            }

            // IDs card
            AnimatedVisibility(visible = idsExpanded) {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("IDs", style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { idsExpanded = false }) {
                                Icon(Icons.Filled.ExpandLess, contentDescription = "Close")
                            }
                        }


                        Text("deviceHash=${deviceHash.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("uid=${myUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                        Text("ownerUid=${ownerUid.ifBlank { "(blank)" }}", fontFamily = FontFamily.Monospace)
                    }
                }
            }

            if (error != null) {
                ErrorCard(error!!)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { setErr(null) }) { Text("Clear error") }
                }
            }

            // Tabs
            ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 0.dp) {
                tabs.forEachIndexed { i, label ->
                    Tab(
                        selected = tabIndex == i,
                        onClick = { tabIndex = i },
                        text = {
                            Text(
                                label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    )
                }
            }

            // Content gets remaining height
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when (tabIndex) {
                    0 -> DashboardTab(
                        db = db,
                        users = sharedUsers,
                        usersLoading = sharedUsersLoading,
                        totalUsersCount = totalUsersCount,
                        warnedUsersCount = warnedUsersCount,
                        bannedUsersCount = bannedUsersCount,
                        onRefresh = {
                            // One-shot model: bumping refreshTick re-fetches the
                            // directory AND re-runs the stats aggregations (both
                            // effects are keyed on refreshTick).
                            refreshTick++
                        },
                        setError = ::setErr
                    )

                    1 -> UsersTab(
                        db = db,
                        myDeviceHash = deviceHash,
                        users = sharedUsers,
                        usersLoading = sharedUsersLoading,
                        liveLimit = sharedLiveLimit,
                        onIncreaseLiveLimit = {
                            sharedLiveLimit = (sharedLiveLimit + 500).coerceAtMost(10000)
                        },
                        onRefresh = { refreshTick++ },
                        // Patch the open user's row in the in-memory directory with
                        // the liveness fields the 10s detail poll ALREADY fetched.
                        // Zero extra Firestore cost — no new read/write, just a local
                        // state merge — so backing out of the detail shows the fresh
                        // online/offline status immediately instead of the stale
                        // one-shot snapshot. Also refresh the local cache so it
                        // survives tab re-entry (SharedPreferences, not Firestore).
                        onUpdateUserRow = { docId, detail ->
                            val idx = sharedUsers.indexOfFirst { it.docId == docId }
                            if (idx >= 0) {
                                val cur = sharedUsers[idx]
                                val patched = cur.copy(
                                    lastActiveAt = detail.lastActiveAt ?: cur.lastActiveAt,
                                    lastSeenAt = detail.lastSeenAt ?: cur.lastSeenAt,
                                    updatedAt = detail.updatedAt ?: cur.updatedAt,
                                    offlineAt = detail.offlineAt ?: cur.offlineAt,
                                    isOnlineInApp = detail.isOnlineInApp,
                                    vrchatUserId = detail.vrchatUserId.ifBlank { cur.vrchatUserId },
                                    vrchatDisplayName = detail.vrchatDisplayName.ifBlank { cur.vrchatDisplayName },
                                    vrchatState = detail.vrchatState.ifBlank { cur.vrchatState },
                                    vrchatStatus = detail.vrchatStatus.ifBlank { cur.vrchatStatus },
                                    vrchatIsOnline = detail.vrchatIsOnline,
                                    vrchatWorld = detail.vrchatWorld.ifBlank { cur.vrchatWorld },
                                    vrchatLastSyncAt = detail.vrchatLastSyncAt ?: cur.vrchatLastSyncAt
                                )
                                if (patched != cur) {
                                    sharedUsers = sharedUsers.toMutableList().also { it[idx] = patched }
                                    UsersDirectoryCache.save(ctx, sharedUsers)
                                }
                            }
                        },
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr,
                        onSendToModeration = { target ->
                            moderationTarget = target
                            tabIndex = 2
                        }
                    )

                    2 -> ModerationTab(
                        db = db,
                        myUid = myUid,
                        byDeviceHash = deviceHash,
                        byAppId = BuildConfig.APPLICATION_ID,
                        clipboardCopy = { },
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr,
                        initialTarget = moderationTarget,
                        onClearInitialTarget = { moderationTarget = null }
                    )

                    3 -> AnnouncementsTab(
                        db = db,
                        createdByDevice = deviceHash,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    4 -> ReleasesTab(
                        db = db,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    5 -> ConfigTab(
                        db = db,
                        setGlobalLoading = { globalLoading = it },
                        setError = ::setErr
                    )

                    else -> ModLogTab(db = db, setError = ::setErr)
                }
            }

            if (globalLoading) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/* =========================================================
   COMMON UI
   ========================================================= */

@Composable
internal fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Error", style = MaterialTheme.typography.titleSmall)
            Text(message, fontFamily = FontFamily.Monospace)
        }
    }
}

/* =========================================================
   SMALL HELPERS
   ========================================================= */

internal fun shortId(s: String, head: Int = 10, tail: Int = 6): String {
    val t = s.trim()
    if (t.isBlank()) return "(blank)"
    if (t.length <= head + tail + 1) return t
    return t.take(head) + "..." + t.takeLast(tail)
}

internal fun relativeTime(ts: Timestamp?, nowMs: Long): String {
    if (ts == null) return "?"
    val then = ts.toDate().time
    val diff = nowMs - then
    if (diff < 0) return "0s ago"
    val s = diff / 1000L
    if (s < 60L) return "${s}s ago"
    val m = s / 60L
    if (m < 60L) return "${m}m ago"
    val h = m / 60L
    if (h < 24L) return "${h}h ago"
    val d = h / 24L
    return "${d}d ago"
}

/* =========================================================
   PREFS HELPERS
   ========================================================= */

internal fun readDeviceHash(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    return prefs.getString("device_id_hash", "")?.trim().orEmpty()
}

internal fun readCachedUid(ctx: Context): String {
    val prefs = ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
    return prefs.getString("auth_uid", "")?.trim().orEmpty()
}

internal fun writeCachedUid(ctx: Context, uid: String) {
    ctx.getSharedPreferences("vrca_remote", Context.MODE_PRIVATE)
        .edit()
        .putString("auth_uid", uid.trim())
        .apply()
}

internal fun formatTimestamp(ts: com.google.firebase.Timestamp?): String {
    if (ts == null) return "?"
    val ms = ts.seconds * 1000L + (ts.nanoseconds / 1_000_000L)
    if (ms <= 0L) return "?"
    val now = System.currentTimeMillis()
    val diff = now - ms

    // Future timestamps or clock skew: just show a compact date.
    if (diff < 0L) {
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    }

    val sec = diff / 1000L
    val min = sec / 60L
    val hr = min / 60L
    val day = hr / 24L

    val rel = when {
        sec < 60L -> "${sec}s ago"
        min < 60L -> "${min}m ago"
        hr < 48L -> "${hr}h ago"
        else -> "${day}d ago"
    }

    val abs = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
    return "$abs ($rel)"
}
