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
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
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
// Staleness guard for the targeted directory liveness refresh — a process-lifetime
// timestamp of the last server pull (full fetch OR targeted cached-user refresh).
// Init 0 so the first directory render after a cold launch always refreshes.
//
// "Once per cold launch" is NOT enough: an admin who keeps the app alive in the
// background for hours/days never restarts the process, so a one-shot guard would
// never re-fire and the cached dots would decay to offline and never recover. So
// instead we refresh whenever the directory is shown (tab entry OR app returns to
// the foreground) AND the data is older than [DIRECTORY_REFRESH_TTL_MS]. Within
// the TTL, plain tab switches / brief resumes cost 0 reads.
private val lastDirectoryRefreshMs = java.util.concurrent.atomic.AtomicLong(0L)
// How stale the cached directory may get before a foreground-return / tab-entry
// triggers a targeted liveness refresh. 10 min keeps the online dots reasonably
// fresh on return without being chatty; the admin can hit Refresh for instant,
// and an open user's 10s detail poll keeps that one row live regardless.
private const val DIRECTORY_REFRESH_TTL_MS = 10L * 60L * 1000L
// Safety margin subtracted from the refresh cursor to absorb client/server clock
// skew so an update whose server timestamp lands just under our wall-clock cursor
// is never missed (worst case: a few already-seen rows re-read; next refresh
// settles it).
private const val REFRESH_SKEW_MARGIN_MS = 5L * 60L * 1000L
// Cadence of the LIVE directory refresh that runs WHILE the Dashboard/Users tab is
// open. The TTL-gated refresh above only fires on tab-entry / foreground-return, so
// an admin who just SITS on the directory never re-reads — a user who swiped (writes
// offlineAt, not lastActiveAt) then lingered "online" until the 65-min local decay.
// This periodic incremental refresh re-reads only docs whose lastActiveAt OR offlineAt
// advanced since the cursor (activity-bounded — under the hourly model an idle base is
// just the per-query minimum), so a swipe surfaces within one cycle without a full scan.
private const val DIRECTORY_LIVE_REFRESH_MS = 30L * 1000L
// The total-user count() value at which the directory last ran a reconcile full
// pull. A doc with NEITHER lastActiveAt NOR offlineAt — a brand-new user still on
// an app version predating the hourly liveness model — can never match the
// incremental whereGreaterThan queries (Firestore inequality filters exclude docs
// missing the field entirely), so it would never enter the cache no matter how
// often the admin refreshes. The already-paid total count() exposes the
// divergence; one bounded full pull resyncs. Throttled per count VALUE: if a
// reconcile didn't resolve the mismatch (a permanent skew between the count's
// adminBuild filter and the directory's self-filter), re-fetching won't either,
// so we wait for the count to actually change before trying again.
private val lastReconciledTotalCount = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)

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
    // Seed SYNCHRONOUSLY from the on-disk directory cache (not empty) so that when the
    // media file picker recreates the Activity, the directory is populated from frame
    // one — otherwise the selected-user detail view (gated on the user being present)
    // is DISPOSED for a frame, losing its saveable scroll/editor state and bouncing the
    // admin back to the top. The async refresh below still runs and replaces this.
    var sharedUsers by remember { mutableStateOf<List<UserRow>>(UsersDirectoryCache.load(ctx)) }
    // Default fetch ceiling raised from 500 → 2000 so the whole base loads (no
    // "only the 500 most-recently-active show" cap); +500 can still raise it.
    var sharedLiveLimit by rememberSaveable { mutableIntStateOf(2000) }
    var sharedUsersLoading by remember { mutableStateOf(true) }
    var refreshTick by remember { mutableIntStateOf(0) }
    // Bumped each time the app returns to the foreground (ON_RESUME). Folding it
    // into the directory effect's keys makes a TTL-gated targeted refresh re-fire
    // when the admin comes back after backgrounding — covering the long-lived
    // process case where the cold-launch refresh never re-runs.
    var resumeTick by remember { mutableIntStateOf(0) }
    val needsUsers = tabIndex == 0 || tabIndex == 1

    // True only while the admin app is in the FOREGROUND (between ON_RESUME and
    // ON_PAUSE). The 30s live directory refresh is gated on this so a backgrounded
    // admin app — even one left open ON the Users/Dashboard tab — stops polling
    // Firestore. Compose keeps the composition (and its LaunchedEffects) alive when
    // backgrounded, so without this gate the live loop reads every 30s all night
    // with nobody looking. On return to foreground, resumeTick bumps and the
    // TTL-gated refresh re-freshens the directory.
    var isForeground by remember { mutableStateOf(true) }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                    isForeground = true; resumeTick++
                    // Resume the watcher/sweep WRITES (the latched browsing/selectedUser
                    // intent is preserved, so they pick up exactly where they left off).
                    AdminRuntime.setForeground(true)
                }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                    isForeground = false
                    // Backgrounding the admin app (no swipe/Activity destroy) leaves the
                    // intent latched; gate the periodic writes off so a user isn't kept
                    // in 10s live-sync while no admin is actually looking.
                    AdminRuntime.setForeground(false)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

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

    // Bounded incremental liveness refresh: read ONLY users whose `lastActiveAt`
    // advanced (came online / hourly write / watched) OR whose `offlineAt` advanced
    // (recently swiped) since the cursor, then merge over the cached list. Never scans
    // the whole collection; idle base ≈ the per-query minimum. Shared by the TTL-gated
    // entry/resume refresh AND the periodic live loop below.
    suspend fun incrementalDirectoryRefresh(includeLegacySweep: Boolean = true) {
        try {
            val last = lastDirectoryRefreshMs.get()
            val cursorMs = if (last > 0L) last - REFRESH_SKEW_MARGIN_MS
                           else System.currentTimeMillis() - ONLINE_STALENESS_WINDOW_MS
            val cursorTs = Timestamp(java.util.Date(cursorMs))
            val activeSnap = db.collection("users")
                .whereGreaterThan("lastActiveAt", cursorTs)
                .orderBy("lastActiveAt", Query.Direction.DESCENDING)
                .limit(sharedLiveLimit.toLong())
                .get(Source.SERVER)
                .await()
            // Recently-swiped users (offlineAt advanced, lastActiveAt did not).
            val offlineSnap = db.collection("users")
                .whereGreaterThan("offlineAt", cursorTs)
                .orderBy("offlineAt", Query.Direction.DESCENDING)
                .limit(sharedLiveLimit.toLong())
                .get(Source.SERVER)
                .await()
            // Legacy-version sweep: a user on an app version predating the hourly
            // model writes ONLY lastSeenAt (never lastActiveAt), so the two queries
            // above can never refresh their row — their cached liveness would decay
            // to offline while their app is alive and heartbeating. Only runs while
            // the cache actually CONTAINS such a row (self-extinguishing once the
            // stragglers update or vanish), and only from the tab-entry/TTL/manual
            // paths, not the 30s live loop — current-version users mirror lastSeenAt
            // on every liveness write, so this query re-reads roughly the same
            // recently-active docs as the lastActiveAt query (~2x reads per run).
            val legacyDocs = if (includeLegacySweep &&
                sharedUsers.any { it.lastActiveAt == null && it.lastSeenAt != null }
            ) {
                db.collection("users")
                    .whereGreaterThan("lastSeenAt", cursorTs)
                    .orderBy("lastSeenAt", Query.Direction.DESCENDING)
                    .limit(sharedLiveLimit.toLong())
                    .get(Source.SERVER)
                    .await()
                    .documents
            } else emptyList()
            val fresh = (activeSnap.documents + offlineSnap.documents + legacyDocs)
                .filter { it.id != deviceHash }
                .associateBy { it.id }          // de-dupe docs hit by both queries
                .values
                .map { parseUserRow(it) }
            if (fresh.isNotEmpty()) {
                val byId = LinkedHashMap<String, UserRow>(sharedUsers.size + fresh.size)
                sharedUsers.forEach { byId[it.docId] = it }
                fresh.forEach { byId[it.docId] = it }
                val merged = byId.values.sortedByDescending {
                    (it.lastActiveAt ?: it.lastSeenAt)?.toDate()?.time ?: 0L
                }
                sharedUsers = merged
                UsersDirectoryCache.save(ctx, merged)
            }
            lastDirectoryRefreshMs.set(System.currentTimeMillis())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("AdminScreen", "Directory liveness refresh failed", e)
        }
    }

    // One bounded full pull (≤ sharedLiveLimit docs) that REPLACES the cache.
    // Used by the first-ever load (nothing cached) and the count-mismatch
    // reconcile below — server truth: missing users appear, deleted ghosts drop.
    suspend fun fullDirectoryFetch(key: String) {
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
            // A full fetch already freshened everything — reset the staleness
            // clock so the targeted refresh doesn't also fire right after.
            lastDirectoryRefreshMs.set(System.currentTimeMillis())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("AdminScreen", "Users fetch failed", e)
            setErr(e.message ?: "Users load failed")
        }
    }

    LaunchedEffect(needsUsers, sharedLiveLimit, refreshTick, resumeTick) {
        if (!needsUsers) return@LaunchedEffect
        // NOTE: `key` deliberately excludes resumeTick — a foreground-return must
        // NOT count as an explicit refresh (no full collection scan); it only
        // re-runs this effect so the TTL-gated targeted cached-user refresh can fire.
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
        //    When the cache already has data, a manual Refresh uses the cheap
        //    incremental path instead of re-reading the whole collection.
        val explicitRefresh = key != lastFetchedKey
        // +500 paging changes the LIMIT — that must be a genuine full pull: the
        // incremental path only refreshes/merges recently-active docs and can
        // never extend the loaded base. A plain Refresh (same limit, refreshTick
        // bumped) stays on the cheap incremental path; the count-mismatch
        // reconcile in the stats effect catches any structurally-missed docs.
        val limitChanged = lastFetchedKey.substringBefore(':') != "$sharedLiveLimit"
        if (explicitRefresh && !limitChanged && sharedUsers.isNotEmpty()) {
            lastFetchedKey = key
            incrementalDirectoryRefresh()
        } else if (sharedUsers.isEmpty() || explicitRefresh) {
            sharedUsersLoading = sharedUsers.isEmpty()
            fullDirectoryFetch(key)
        } else if (System.currentTimeMillis() - lastDirectoryRefreshMs.get() > DIRECTORY_REFRESH_TTL_MS) {
            // Directory shown (tab entry OR foreground-return via resumeTick) with a
            // populated cache that's gone stale: targeted INCREMENTAL liveness refresh
            // (see incrementalDirectoryRefresh — reads only advanced lastActiveAt /
            // offlineAt docs, never a full scan). On the first refresh of a session the
            // cursor falls back to the online window. The clock is stamped only on
            // SUCCESS inside the helper: the initial ON_RESUME replay bumps resumeTick
            // right after launch and cancels this run — stamping up-front would make the
            // restarted run skip and lose the refresh.
            incrementalDirectoryRefresh()
        }
        sharedUsersLoading = false
    }

    // LIVE directory refresh: while the Dashboard/Users tab is composed, re-run the
    // bounded incremental refresh every DIRECTORY_LIVE_REFRESH_MS so a user's swipe
    // (offlineAt) or a newly-online user surfaces without the admin leaving and
    // re-entering the tab. Auto-cancels when switching to a non-directory tab
    // (needsUsers=false) or leaving the panel. Skips a cycle if a full fetch / TTL
    // refresh just freshened everything, so it never double-reads.
    LaunchedEffect(needsUsers, isForeground) {
        if (!needsUsers || !isForeground) return@LaunchedEffect
        while (true) {
            delay(DIRECTORY_LIVE_REFRESH_MS)
            if (sharedUsers.isNotEmpty() &&
                System.currentTimeMillis() - lastDirectoryRefreshMs.get() >= DIRECTORY_LIVE_REFRESH_MS) {
                // No legacy sweep on the 30s loop — it would re-read the whole
                // recently-active set every cycle while a straggler exists.
                // Stragglers refresh at tab-entry/TTL/manual granularity instead.
                incrementalDirectoryRefresh(includeLegacySweep = false)
            }
        }
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

            // Reconcile: a doc the incremental refresh structurally can't see (a
            // new user on a pre-hourly-model app version — no lastActiveAt, no
            // offlineAt; Firestore inequality filters skip docs missing the field)
            // is still counted here, so a count that disagrees with the cached row
            // count means the directory has diverged from the truth. One bounded
            // full pull resyncs it. Skipped while the directory's own first load
            // is in flight (it owns that fetch) and throttled per count value
            // (see lastReconciledTotalCount).
            if (sharedUsers.isNotEmpty() && !sharedUsersLoading &&
                totalUsersCount != sharedUsers.size &&
                lastReconciledTotalCount.get() != totalUsersCount
            ) {
                lastReconciledTotalCount.set(totalUsersCount)
                fullDirectoryFetch("$sharedLiveLimit:$refreshTick")
            }

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
    // Clear the watch/browse intent when the admin LEAVES the panel (page
    // navigation disposes AdminScreen). Without this, AdminRuntime — which is
    // process-lifetime so watching survives mere backgrounding — keeps a stale
    // selectedUser and its watcherActiveAt heartbeat writes every 30s FOREVER even
    // though the admin is no longer in the panel (the "constant ~2 writes/min while
    // not in the admin panel" leak). UsersTab only clears selectedUser when you back
    // OUT of a detail; navigating straight to another tab with a detail open never
    // did. Backgrounding does NOT dispose the composable, so an actively-watching
    // admin who backgrounds the app still keeps the watch — only leaving the panel
    // (or the Activity being destroyed) stops it.
    DisposableEffect(Unit) {
        onDispose {
            AdminRuntime.setSelectedUser(null)
            AdminRuntime.setBrowsing(false)
        }
    }
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
                                    // Authoritative (the detail poll read the whole doc):
                                    // a null clears a synthetic/stale swipe marker so a
                                    // healed user returns online; the watched-stale path
                                    // synthesizes offlineAt here to flip the row offline.
                                    offlineAt = detail.offlineAt,
                                    killSignal = detail.killSignal ?: cur.killSignal,
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
