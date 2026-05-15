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
import androidx.compose.runtime.DisposableEffect
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
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.delay
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
    // Uses a Firestore snapshot listener filtered to online users only.
    // Polling 500 docs every 30s = 1000 reads/min flat regardless of changes.
    // Snapshot listener: pays N reads on initial attach, then only delta reads
    // when docs change. Filtering to isOnlineInApp=true further cuts the
    // initial fetch (typically <50 online vs hundreds total). The listener
    // also pushes changes in real-time so the dashboard online counter
    // updates without requiring a tab switch.
    var sharedUsers by remember { mutableStateOf<List<UserRow>>(emptyList()) }
    var sharedLiveLimit by rememberSaveable { mutableIntStateOf(500) }
    var sharedUsersLoading by remember { mutableStateOf(true) }
    val needsUsers = tabIndex == 0 || tabIndex == 1

    // Stats from count() aggregations — much cheaper than reading all docs.
    var totalUsersCount by remember { mutableIntStateOf(0) }
    var warnedUsersCount by remember { mutableIntStateOf(0) }
    var bannedUsersCount by remember { mutableIntStateOf(0) }

    DisposableEffect(needsUsers, sharedLiveLimit) {
        if (!needsUsers) {
            return@DisposableEffect onDispose { }
        }
        sharedUsersLoading = true
        val reg = db.collection("users")
            .orderBy("lastSeenAt", Query.Direction.DESCENDING)
            .limit(sharedLiveLimit.toLong())
            .addSnapshotListener { snap, err ->
                if (err != null) {
                    setErr(err.message ?: "Users load failed")
                    sharedUsersLoading = false
                    return@addSnapshotListener
                }
                if (snap != null) {
                    sharedUsers = snap.documents
                        .filter { it.id != deviceHash }
                        .map { parseUserRow(it) }
                    sharedUsersLoading = false
                }
            }
        onDispose { reg.remove() }
    }

    // Aggregate stats: refresh every 60s while admin is on Dashboard/Users.
    // count() aggregation costs 1 read per 1000 docs counted (very cheap).
    LaunchedEffect(needsUsers) {
        if (!needsUsers) return@LaunchedEffect
        while (true) {
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
            delay(60_000L)
        }
    }

    // Admin browsing heartbeat: while on Dashboard or Users tab, write
    // config/adminPresence.browsingAt every 30s so user apps start their
    // own lastSeenAt heartbeats. After a 90s grace period, sweep for users
    // whose isOnlineInApp=true but lastSeenAt is stale — these are
    // force-killed apps that never wrote their offline state. Force them
    // offline so the directory stays accurate.
    LaunchedEffect(needsUsers) {
        if (!needsUsers) return@LaunchedEffect
        val browsingStartedAt = System.currentTimeMillis()
        while (true) {
            try {
                db.collection("config")
                    .document("adminPresence")
                    .set(
                        mapOf("browsingAt" to FieldValue.serverTimestamp()),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
            } catch (_: Throwable) {}

            val elapsed = System.currentTimeMillis() - browsingStartedAt
            if (elapsed > 90_000L) {
                val now = System.currentTimeMillis()
                for (u in sharedUsers) {
                    if (!u.isOnlineInApp) continue
                    val seenMs = u.lastSeenAt?.toDate()?.time ?: 0L
                    if (now - seenMs > ONLINE_STALENESS_WINDOW_MS) {
                        try {
                            db.collection("users").document(u.docId)
                                .set(
                                    mapOf("isOnlineInApp" to false),
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                        } catch (_: Throwable) {}
                    }
                }
            }

            delay(30_000L)
        }
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
                            // Snapshot listener auto-refreshes; refresh only re-triggers stats
                            scope.launch {
                                try {
                                    val totalSnap = db.collection("users")
                                        .whereEqualTo("adminBuild", false)
                                        .count()
                                        .get(com.google.firebase.firestore.AggregateSource.SERVER)
                                        .await()
                                    totalUsersCount = totalSnap.count.toInt()
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    setErr(e.message ?: "Refresh failed")
                                }
                            }
                        },
                        setError = ::setErr
                    )

                    1 -> UsersTab(
                        db = db,
                        myDeviceHash = deviceHash,
                        users = sharedUsers,
                        liveLimit = sharedLiveLimit,
                        onIncreaseLiveLimit = {
                            sharedLiveLimit = (sharedLiveLimit + 500).coerceAtMost(10000)
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
