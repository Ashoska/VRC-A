package com.vrca.admin

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.vrca.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/* =========================================================
   USERS TAB (LIVE / REACTIVE)
   ========================================================= */

internal data class UserRow(
    val docId: String,
    val authUid: String,
    val displayName: String,
    val deviceHash: String,
    val warned: Boolean,
    val banned: Boolean,
    val lastActiveAt: Timestamp?,
    val lastSeenAt: Timestamp?,
    val updatedAt: Timestamp?,
    // VRChat
    val vrchatUserId: String = "",
    val vrchatDisplayName: String = "",
    val vrchatState: String = "",
    val vrchatStatus: String = "",
    val vrchatIsOnline: Boolean = false,
    val vrchatWorld: String = "",
    val vrchatPlayerCount: Int = 0,
    val vrchatCapacity: Int = 0,
    val vrchatPlatform: String = "",
    val vrchatLastSyncAt: Timestamp? = null,
    val isOnlineInApp: Boolean = false,
    // Clean-shutdown marker (swipe-away). When present AND newer than
    // lastActiveAt it forces the row offline instantly; a live heartbeat
    // advances lastActiveAt past it, so it can never false-flag a running app.
    val offlineAt: Timestamp? = null
    // Profile pictures are NOT stored in Firestore — AdminAvatar resolves the
    // VRChat+ picture on demand by vrchatUserId via the admin's VRChat session.
)

internal data class UserDetail(
    // Pinned message (was AFK)
    val pinnedEnabled: Boolean,
    val pinnedMessage: String,
    val pinnedPresets: List<String>,
    // Cycle
    val cycleEnabled: Boolean,
    val cycleIntervalSeconds: Long,
    val cycleLinesText: String,
    val cyclePresets: List<String>,
    // Now Playing
    val spotifyEnabled: Boolean,
    val spotifyDemoEnabled: Boolean,
    val spotifyPreset: Long,
    val nowPlayingDetected: Boolean,
    val nowPlayingIsPlaying: Boolean,
    val nowPlayingTitle: String,
    val nowPlayingArtist: String,
    val nowPlayingPackage: String,
    // Output
    val combinedPreviewText: String,
    // Moderation
    val warnReason: String,
    val banReason: String,
    // Network
    // App info
    val versionName: String,
    val versionCode: Long,
    val appId: String,
    val adminBuild: Boolean,
    // VRChat
    val vrchatUserId: String,
    val vrchatDisplayName: String,
    val vrchatState: String,
    val vrchatStatus: String,
    val vrchatIsOnline: Boolean,
    val vrchatStatusDescription: String,
    val vrchatWorld: String,
    val vrchatLocation: String,
    val vrchatPlayerCount: Long,
    val vrchatCapacity: Long,
    val vrchatPlatform: String,
    val timeEnabled: Boolean = false,
    val vrchatLastSyncAt: Timestamp?,
    // Live liveness timestamps from the 10s detail poll (the header shows these
    // so "active/updated" refresh in real time instead of reflecting the stale
    // one-shot directory snapshot).
    val lastActiveAt: Timestamp? = null,
    val lastSeenAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val isOnlineInApp: Boolean = false,
    val offlineAt: Timestamp? = null
)

internal data class ModerationTarget(
    val docId: String,
    val authUid: String,
    val deviceHash: String,
    val displayName: String,
    val vrchatUserId: String = "",
    val banReason: String = "",
    val warnReason: String = "",
    val banned: Boolean = false,
    val warned: Boolean = false
)

/**
 * Online is determined by LIVENESS FRESHNESS, not the `isOnlineInApp` flag.
 *
 * `lastActiveAt` is the ground truth: the app's hourly heartbeat (every 10s while
 * watched) only fires while the process is alive, so a fresh `lastActiveAt` proves
 * the app is running. The old logic gated on `isOnlineInApp == true` first, but
 * that flag is unreliable — pre-app-scoped-VM builds wrote `isOnlineInApp = false`
 * from `onCleared()` on every Activity destruction (normal under memory pressure)
 * WITHOUT advancing `lastActiveAt`, so a perfectly-live user showed offline with a
 * fresh timestamp (the "Cherubic shows offline while clearly online" bug). We now
 * IGNORE `isOnlineInApp` for the online decision.
 *
 * Rules:
 *  - `lastActiveAt` (fallback `lastSeenAt`) within ~65 min  → ONLINE
 *    (5 min grace past the 60-min hourly heartbeat; a watched user refreshes every
 *     10s so shows online within ~25s).
 *  - Stale beyond the window → OFFLINE.
 *  - A clean shutdown (swipe-away) stamps `offlineAt`. When `offlineAt` is NEWER
 *    than `lastActiveAt` the row is forced OFFLINE INSTANTLY (no 65-min wait). This
 *    can never false-flag a live app: the next heartbeat advances `lastActiveAt`
 *    past `offlineAt`. If the swipe-time offline write never lands (network race —
 *    the long-standing "swipe doesn't consistently go offline"), the staleness
 *    window still flips the user offline within ~65 min. Best of both: instant when
 *    the write succeeds, eventually-consistent when it doesn't.
 *
 * `isOnlineInApp` is deliberately NOT consulted: old builds write it `false` from
 * `onCleared()` on routine Activity destruction (memory pressure) while still alive
 * and without advancing `lastActiveAt`, so trusting it would re-introduce the
 * "live user shows offline" bug.
 */
internal const val ONLINE_STALENESS_WINDOW_MS = 65L * 60L * 1000L

/**
 * Tighter liveness window used ONLY while an admin is actively watching a user's
 * detail page. A watched user's app writes a liveness timestamp every 10s (the
 * `lastSeenAt` live-loop write is dependency-free, so it's the reliable heartbeat),
 * so a heartbeat that stops advancing for this long while watched is a reliable
 * "the app died/was swiped" signal — far faster than the 65-min unwatched window.
 * Set to ~2.5 missed 10s ticks: fast enough to feel immediate, with enough slack to
 * ride out a single delayed write. This is the robust swipe→offline path because it
 * does NOT depend on the swipe-time `offlineAt` write landing during shutdown.
 */
internal const val WATCHED_STALE_WINDOW_MS = 25L * 1000L

/**
 * Grace period before the tight [WATCHED_STALE_WINDOW_MS] applies WHEN the user's
 * 10s heartbeat has not yet been observed advancing (its live loop starts only after
 * the `watcherActiveAt` heartbeat propagates — up to ~30-60s). Once the detail poll
 * HAS seen the heartbeat advance (`loopConfirmed`), this ramp is bypassed and a
 * stopped heartbeat flips offline after just [WATCHED_STALE_WINDOW_MS].
 */
internal const val WATCH_RAMP_MS = 90L * 1000L

internal fun isUserOnline(u: UserRow, nowMs: Long = System.currentTimeMillis()): Boolean {
    val activeMs = (u.lastActiveAt ?: u.lastSeenAt)?.toDate()?.time ?: return false
    if (nowMs - activeMs >= ONLINE_STALENESS_WINDOW_MS) return false
    // Instant-offline on a clean swipe: a shutdown marker newer than the last
    // liveness write means the app was deliberately closed after that heartbeat.
    val offlineMs = u.offlineAt?.toDate()?.time ?: 0L
    if (offlineMs > activeMs) return false
    return true
}

internal fun parseUserRow(d: com.google.firebase.firestore.DocumentSnapshot): UserRow {
    val docId = d.id
    val authUid = (d.getString("authUid") ?: d.getString("uid") ?: "").trim()
    return UserRow(
        docId = docId, authUid = authUid,
        displayName = (d.getString("displayName") ?: "").trim(),
        deviceHash = (d.getString("deviceHash") ?: "").trim(),
        warned = d.getBoolean("warned") ?: false,
        banned = d.getBoolean("banned") ?: false,
        lastActiveAt = d.getTimestamp("lastActiveAt"),
        lastSeenAt = d.getTimestamp("lastSeenAt"),
        updatedAt = d.getTimestamp("updatedAt"),
        vrchatUserId = (d.getString("vrchatUserId") ?: "").trim(),
        vrchatDisplayName = (d.getString("vrchatDisplayName") ?: "").trim(),
        vrchatState = (d.getString("vrchatState") ?: "").trim(),
        vrchatStatus = (d.getString("vrchatStatus") ?: "").trim(),
        vrchatIsOnline = d.getBoolean("vrchatIsOnline") ?: false,
        vrchatWorld = (d.getString("vrchatWorld") ?: "").trim(),
        vrchatPlayerCount = (d.getLong("vrchatInstancePlayerCount") ?: 0).toInt(),
        vrchatCapacity = (d.getLong("vrchatInstanceCapacity") ?: 0).toInt(),
        vrchatPlatform = (d.getString("vrchatPlatform") ?: "").trim(),
        vrchatLastSyncAt = d.getTimestamp("vrchatLastSyncAt"),
        isOnlineInApp = d.getBoolean("isOnlineInApp") ?: false,
        offlineAt = d.getTimestamp("offlineAt")
    )
}

@Composable
internal fun UsersTab(
    db: FirebaseFirestore,
    myDeviceHash: String,
    users: List<UserRow>,
    usersLoading: Boolean,
    liveLimit: Int,
    onIncreaseLiveLimit: () -> Unit,
    onRefresh: () -> Unit,
    onUpdateUserRow: (String, UserDetail) -> Unit,
    setGlobalLoading: (Boolean) -> Unit,
    setError: (String?) -> Unit,
    onSendToModeration: (ModerationTarget) -> Unit
) {
    var search       by rememberSaveable { mutableStateOf("") }
    var filterWarned by rememberSaveable { mutableStateOf(false) }
    var filterBanned by rememberSaveable { mutableStateOf(false) }
    var selectedDocId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var selectedDetail        by remember { mutableStateOf<UserDetail?>(null) }
    var selectedDetailLoading by remember { mutableStateOf(false) }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(5_000L) }
    }

    fun rowMatches(u: UserRow, q: String): Boolean {
        if (q.isBlank()) return true
        val t = q.trim()
        return u.docId.contains(t, true) || u.authUid.contains(t, true) ||
            u.deviceHash.contains(t, true) || u.displayName.contains(t, true) ||
            u.vrchatUserId.contains(t, true) || u.vrchatDisplayName.contains(t, true) ||
            u.vrchatWorld.contains(t, true)
    }

    val filteredUsers by remember {
        derivedStateOf {
            val q = search.trim()
            users.asSequence()
                .filter { if (filterWarned) it.warned else true }
                .filter { if (filterBanned) it.banned else true }
                .filter { rowMatches(it, q) }
                .toList()
        }
    }


    fun parseUserDetail(snap: com.google.firebase.firestore.DocumentSnapshot): UserDetail {
        fun s(key: String) = (snap.getString(key) ?: "").trim()
        fun b(key: String) = snap.getBoolean(key) ?: false
        fun l(key: String) = snap.getLong(key) ?: 0L
        return UserDetail(
            pinnedEnabled  = b("afkEnabled"), pinnedMessage = s("afkMessage"),
            pinnedPresets  = listOf(s("afkPreset1"), s("afkPreset2"), s("afkPreset3")),
            cycleEnabled   = b("cycleEnabled"), cycleIntervalSeconds = l("cycleIntervalSeconds"),
            cycleLinesText = s("cycleLinesText"),
            cyclePresets   = listOf(s("cyclePreset1"), s("cyclePreset2"), s("cyclePreset3"), s("cyclePreset4"), s("cyclePreset5")),
            spotifyEnabled = b("spotifyEnabled"), spotifyDemoEnabled = b("spotifyDemoEnabled"),
            spotifyPreset  = l("spotifyPreset"),
            nowPlayingDetected = b("nowPlayingDetected"), nowPlayingIsPlaying = b("nowPlayingIsPlaying"),
            nowPlayingTitle = s("nowPlayingTitle"), nowPlayingArtist = s("nowPlayingArtist"),
            nowPlayingPackage = s("activePackage"),
            combinedPreviewText = s("combinedPreviewText"),
            warnReason = s("warnReason"), banReason = s("banReason"),
            versionName = s("versionName"), versionCode = l("versionCode"), appId = s("appId"),
            adminBuild = b("adminBuild"),
            vrchatUserId = s("vrchatUserId"), vrchatDisplayName = s("vrchatDisplayName"),
            vrchatState = s("vrchatState"), vrchatStatus = s("vrchatStatus"),
            vrchatIsOnline = snap.getBoolean("vrchatIsOnline") ?: false,
            vrchatStatusDescription = s("vrchatStatusDescription"),
            vrchatWorld = s("vrchatWorld"), vrchatLocation = s("vrchatLocation"),
            vrchatPlayerCount = l("vrchatInstancePlayerCount"), vrchatCapacity = l("vrchatInstanceCapacity"),
            vrchatPlatform = s("vrchatPlatform"),
            timeEnabled = b("timeEnabled"),
            vrchatLastSyncAt = snap.getTimestamp("vrchatLastSyncAt"),
            lastActiveAt = snap.getTimestamp("lastActiveAt"),
            lastSeenAt = snap.getTimestamp("lastSeenAt"),
            updatedAt = snap.getTimestamp("updatedAt"),
            isOnlineInApp = snap.getBoolean("isOnlineInApp") ?: false,
            offlineAt = snap.getTimestamp("offlineAt")
        )
    }
    // Selected user detail: Phase 3 read model — the ONLY live read in the admin
    // panel, scoped to the single open user and polled every 10s from the server.
    // This replaces the per-doc snapshot listener (which, combined with the old
    // collection listener, kept reads flowing for the whole directory). The loop
    // is keyed on selectedDocId, so backing out (selectedDocId -> null) cancels
    // this coroutine and the reads stop INSTANTLY. The watcher heartbeat
    // (AdminRuntime) puts the user app into 10s live-sync, so 10s polling here
    // matches the cadence at which the user's volatile fields refresh.
    LaunchedEffect(selectedDocId) {
        val docId = selectedDocId
        if (docId.isNullOrBlank()) {
            selectedDetail = null; selectedDetailLoading = false
            return@LaunchedEffect
        }
        // Watched fast-offline tracking. While watched, the user app writes a liveness
        // timestamp every 10s — `lastSeenAt` via the ViewModel live loop (no external
        // dependency, so it's the RELIABLE heartbeat) and `lastActiveAt` via the
        // presence loop. We track the FRESHEST of the two and the wall-clock time it
        // last advanced. Once we've SEEN it advance (the loop is confirmed running),
        // a swipe/kill stops it and we flip offline after just WATCHED_STALE_WINDOW_MS
        // — no need to wait the full ramp. Before the loop is confirmed (e.g. the
        // user's 10s loop hasn't started yet, or they're already offline), we fall
        // back to the WATCH_RAMP_MS grace so we don't false-flag a live user mid-ramp.
        val watchStartMs = System.currentTimeMillis()
        var freshestSeenMs = 0L
        var lastAdvanceWallMs = watchStartMs
        var loopConfirmed = false
        selectedDetailLoading = true
        while (true) {
            try {
                val snap = db.collection("users").document(docId)
                    .get(Source.SERVER)
                    .await()
                val detail = (if (snap.exists()) parseUserDetail(snap) else null)
                    ?.let { d ->
                        val la = d.lastActiveAt?.toDate()?.time ?: 0L
                        val ls = d.lastSeenAt?.toDate()?.time ?: 0L
                        val freshest = maxOf(la, ls)
                        val offMs = d.offlineAt?.toDate()?.time ?: 0L
                        val now = System.currentTimeMillis()
                        if (freshest > freshestSeenMs) {
                            if (freshestSeenMs != 0L) loopConfirmed = true
                            freshestSeenMs = freshest
                            lastAdvanceWallMs = now
                        }
                        // If the heartbeat has stopped advancing, the app died.
                        // Synthesize offlineAt so isUserOnline flips offline INSTANTLY
                        // here and, via onUpdateUserRow, in the directory too (zero
                        // Firestore write). Self-heals when the heartbeat advances again.
                        val staleWhileWatched = if (loopConfirmed) {
                            now - lastAdvanceWallMs > WATCHED_STALE_WINDOW_MS
                        } else {
                            now - watchStartMs > WATCH_RAMP_MS && freshest > 0L &&
                                now - freshest > WATCHED_STALE_WINDOW_MS
                        }
                        if (staleWhileWatched && offMs <= freshest)
                            d.copy(offlineAt = Timestamp(java.util.Date(now)))
                        else d
                    }
                selectedDetail = detail
                if (detail != null) onUpdateUserRow(docId, detail)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                setError(e.message ?: "User detail load failed")
            } finally {
                selectedDetailLoading = false
            }
            delay(10_000L)
        }
    }

    // Watcher heartbeat (writes watcherActiveAt every 30s while a user is selected)
    // now runs in AdminRuntime at process lifetime, so it keeps the watched user in
    // live-sync mode even when the admin app is backgrounded. We only register the
    // current selection here; clearing it on dispose stops the heartbeat when the
    // detail view truly goes away within the same process.
    LaunchedEffect(selectedDocId) {
        AdminRuntime.setSelectedUser(selectedDocId)
    }
    // Clear the watch the moment the Users tab leaves composition (switching to
    // another admin tab while a detail is open, or leaving the panel) so the 30s
    // watcherActiveAt heartbeat stops — without this it kept writing for whatever
    // user was last open. (Backing out of a detail already clears it via the effect
    // above; this covers the tab-switch / panel-exit paths.)
    DisposableEffect(Unit) {
        onDispose { AdminRuntime.setSelectedUser(null) }
    }

    val selectedRow by remember {
        derivedStateOf {
            selectedDocId?.let { id -> users.firstOrNull { it.docId == id } }
        }
    }

    // ---- Detail view ----
    val rawRow = selectedRow
    if (rawRow != null) {
        val d = selectedDetail
        // Prefer the live 10s detail poll's liveness timestamps over the stale
        // one-shot directory snapshot so the detail online dot is accurate the
        // moment watching kicks in (the directory row can be up to an hour old).
        val row = rawRow.copy(
            lastActiveAt = d?.lastActiveAt ?: rawRow.lastActiveAt,
            lastSeenAt   = d?.lastSeenAt ?: rawRow.lastSeenAt,
            // When the detail poll has loaded, its offlineAt is AUTHORITATIVE (it read
            // the whole doc) — including a null, so a healed user (synthetic offlineAt
            // cleared) goes back online. Only fall back to the directory row's value
            // while the detail hasn't loaded yet (d == null).
            offlineAt    = if (d != null) d.offlineAt else rawRow.offlineAt,
            isOnlineInApp = d?.isOnlineInApp ?: rawRow.isOnlineInApp
        )
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Back row sits above the identity card for a clear hierarchy.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = { selectedDocId = null }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Back to directory",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            ElevatedCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Identity: large avatar + name + VRChat id + status pills.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val primaryLabel = row.vrchatDisplayName.ifBlank {
                            row.displayName.ifBlank { shortId(row.docId) }
                        }
                        // Prefer the live 10s detail poll for the pfp so it
                        // appears/refreshes once watching kicks in; fall back to
                        // the directory snapshot.
                        val liveVrcId = (d?.vrchatUserId ?: "").ifBlank { row.vrchatUserId }
                        AdminAvatar(
                            name = primaryLabel,
                            online = isUserOnline(row, nowMs),
                            size = 60,
                            vrchatUserId = liveVrcId
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                primaryLabel,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            val secondary = if (row.vrchatDisplayName.isNotBlank() &&
                                row.displayName.isNotBlank() && row.vrchatDisplayName != row.displayName)
                                row.displayName else null
                            if (secondary != null) {
                                Text(secondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (isUserOnline(row, nowMs)) StatusPill("ONLINE", AdminTone.Primary)
                                if (row.vrchatIsOnline) StatusPill("VRC", AdminTone.Info)
                                if (row.banned) StatusPill("BANNED", AdminTone.Error)
                                if (row.warned) StatusPill("WARNED", AdminTone.Warn)
                            }
                        }
                    }

                    if (row.vrchatWorld.isNotBlank()) {
                        Text("📍 ${row.vrchatWorld}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    Divider()

                    // Identity facts + a SINGLE liveness timestamp. "active",
                    // "updated" and "synced" were three confusingly-similar fields;
                    // they're collapsed into one "Last seen" (the canonical
                    // lastActiveAt, falling back to lastSeenAt/updatedAt). It comes
                    // from the live 10s detail poll (d) so it refreshes in real
                    // time, falling back to the directory snapshot until the first poll.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (row.vrchatUserId.isNotBlank())
                            AdminLabeledRow("VRChat", row.vrchatUserId, mono = true, labelWidth = 76)
                        AdminLabeledRow("authUid", shortId(row.authUid.ifBlank { "(blank)" }), mono = true, labelWidth = 76)
                        AdminLabeledRow("device",  shortId(row.deviceHash.ifBlank { "(blank)" }), mono = true, labelWidth = 76)
                        val seenTs = d?.lastActiveAt ?: d?.lastSeenAt ?: d?.updatedAt
                            ?: row.lastActiveAt ?: row.lastSeenAt ?: row.updatedAt
                        AdminLabeledRow("last seen", relativeTime(seenTs, nowMs), labelWidth = 76)
                    }

                    Divider()

                    // Actions side-by-side for a tidy footer.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                onSendToModeration(ModerationTarget(
                                    docId = row.docId, authUid = row.authUid,
                                    deviceHash = row.deviceHash,
                                    displayName = row.vrchatDisplayName.ifBlank { row.displayName },
                                    vrchatUserId = row.vrchatUserId,
                                    banned = row.banned, warned = row.warned,
                                    banReason = selectedDetail?.banReason ?: "",
                                    warnReason = selectedDetail?.warnReason ?: ""
                                ))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Gavel, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Moderate")
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    setGlobalLoading(true)
                                    runCatching {
                                        db.collection("users").document(row.docId)
                                            .set(
                                                mapOf("killSignal" to com.google.firebase.firestore.FieldValue.serverTimestamp()),
                                                com.google.firebase.firestore.SetOptions.merge()
                                            )
                                            .await()
                                    }.onFailure { e ->
                                        setError(e.message ?: "Kill failed")
                                    }
                                    setGlobalLoading(false)
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Filled.Power, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Kill App")
                        }
                    }
                }
            }

            if (selectedDetailLoading) {
                ElevatedCard {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(); Text("Loading details...")
                    }
                }
            } else if (d != null) {
                DetailBlock(d = d, docId = selectedDocId ?: "", db = db, setError = setError)
            } else {
                ElevatedCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Details", style = MaterialTheme.typography.titleSmall)
                        Text("No detail loaded (doc missing or not yet written).",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        return
    }

    // ---- List view ----
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        item {
            ElevatedCard {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Users", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${filteredUsers.size} / ${users.size}",
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (usersLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                IconButton(
                                    onClick = onRefresh,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Refresh users",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = onIncreaseLiveLimit,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text("+500", style = MaterialTheme.typography.labelSmall) }
                        }
                    }

                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("Search name / id / uid / device") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null,
                            modifier = Modifier.size(18.dp)) },
                        trailingIcon = if (search.isNotBlank()) ({
                            IconButton(onClick = { search = "" },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Filled.Remove, contentDescription = "Clear",
                                    modifier = Modifier.size(16.dp))
                            }
                        }) else null
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = filterWarned,
                            onClick = { filterWarned = !filterWarned },
                            label = { Text("Warned", style = MaterialTheme.typography.labelSmall) }
                        )
                        FilterChip(
                            selected = filterBanned,
                            onClick = { filterBanned = !filterBanned },
                            label = { Text("Banned", style = MaterialTheme.typography.labelSmall) }
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${filteredUsers.size} of $liveLimit loaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                    }
                }
            }
        }

        if (filteredUsers.isEmpty()) {
            item {
                Text("No users matching current filters.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp))
            }
        }

        items(filteredUsers, key = { it.docId }) { u ->
            val appOnline = isUserOnline(u, nowMs)
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        u.banned -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                        u.warned -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                        else     -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                ),
                modifier = Modifier.fillMaxWidth().clickable { selectedDocId = u.docId }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val primaryName = u.vrchatDisplayName.ifBlank { u.displayName.ifBlank { shortId(u.docId) } }
                    AdminAvatar(
                        name = primaryName,
                        online = appOnline,
                        vrchatUserId = u.vrchatUserId
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val secondaryName = if (u.vrchatDisplayName.isNotBlank() && u.displayName.isNotBlank() && u.vrchatDisplayName != u.displayName) u.displayName else null
                        Text(
                            primaryName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        if (secondaryName != null) {
                            Text(secondaryName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1)
                        }
                        if (u.vrchatWorld.isNotBlank()) {
                            Text("📍 ${u.vrchatWorld}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Status pills + relative time on one wrapping row.
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (appOnline) StatusPill("ONLINE", AdminTone.Primary)
                            if (u.vrchatIsOnline) StatusPill("VRC", AdminTone.Info)
                            if (u.banned) StatusPill("BAN", AdminTone.Error)
                            if (u.warned) StatusPill("WARN", AdminTone.Warn)
                            Text(
                                relativeTime(u.lastActiveAt ?: u.lastSeenAt, nowMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
internal fun DetailBlock(d: UserDetail, docId: String, db: FirebaseFirestore, setError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()

    fun writeField(key: String, value: Any) {
        if (docId.isBlank()) return
        db.collection("users").document(docId)
            .set(mapOf(key to value), SetOptions.merge())
            .addOnFailureListener { e -> setError("Write failed: ${e.message}") }
    }

    // ── VRChat ──────────────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminCardHeader("VRChat", Icons.Filled.SportsEsports, AdminTone.Info)
            if (d.vrchatUserId.isBlank()) {
                Text("Not linked", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val dot = if (d.vrchatIsOnline) {
                    when (d.vrchatStatus) {
                        "join me" -> "🟢"; "ask me" -> "🟠"; "busy" -> "🔴"; else -> "🟢"
                    }
                } else "⚫"
                val onlineLabel = if (d.vrchatIsOnline) "Online" else "Offline"
                Text("$dot ${d.vrchatDisplayName.ifBlank { d.vrchatUserId }} ($onlineLabel)",
                    style = MaterialTheme.typography.bodyMedium)
                if (d.vrchatStatusDescription.isNotBlank())
                    Text(d.vrchatStatusDescription, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (d.vrchatWorld.isNotBlank()) {
                    val cnt = if (d.vrchatCapacity > 0) "${d.vrchatPlayerCount}/${d.vrchatCapacity}"
                              else "${d.vrchatPlayerCount}"
                    Text("📍 ${d.vrchatWorld} ($cnt)", style = MaterialTheme.typography.bodySmall)
                }
                val ctx = LocalContext.current
                Text("ID: ${d.vrchatUserId}", fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://vrchat.com/home/user/${d.vrchatUserId}"))
                        ctx.startActivity(intent)
                    })
                // (No separate "Synced" line — liveness is the single "last seen"
                // field in the identity header.)
            }
        }
    }

    // ── Live Output + Feature Toggles ───────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminCardHeader("Live Chatbox Output", Icons.Filled.Chat, AdminTone.Primary)
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(d.combinedPreviewText.ifBlank { "(nothing sending)" },
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            // Remote toggle chips
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = d.pinnedEnabled, onClick = { writeField("afkEnabled", !d.pinnedEnabled) },
                    label = { Text("Pinned", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = d.cycleEnabled, onClick = { writeField("cycleEnabled", !d.cycleEnabled) },
                    label = { Text("Cycle", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = d.spotifyEnabled, onClick = { writeField("spotifyEnabled", !d.spotifyEnabled) },
                    label = { Text("Music", style = MaterialTheme.typography.labelSmall) })
                FilterChip(selected = d.timeEnabled, onClick = { writeField("timeEnabled", !d.timeEnabled) },
                    label = { Text("Time", style = MaterialTheme.typography.labelSmall) })
            }
            if (d.nowPlayingDetected) {
                val musicCtx = LocalContext.current
                val musicQuery = "${d.nowPlayingTitle} ${d.nowPlayingArtist}".trim()
                val appLabel = when (d.nowPlayingPackage) {
                    "com.spotify.music" -> "Spotify"
                    "com.google.android.youtube" -> "YouTube"
                    "com.google.android.apps.youtube.music" -> "YT Music"
                    "com.apple.android.music" -> "Apple Music"
                    "deezer.android.app" -> "Deezer"
                    "com.soundcloud.android" -> "SoundCloud"
                    "com.amazon.mp3" -> "Amazon Music"
                    "com.bandcamp.android" -> "Bandcamp"
                    else -> null
                }
                val statusIcon = if (d.nowPlayingIsPlaying) "▶" else "⏸"
                val label = buildString {
                    append("🎵 ")
                    append(d.nowPlayingTitle.ifBlank { "?" })
                    append(" — ")
                    append(d.nowPlayingArtist.ifBlank { "?" })
                    append(" $statusIcon")
                    if (appLabel != null) append(" ($appLabel)")
                }
                Text(label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        if (musicQuery.isNotBlank()) {
                            val searchUrl = when (d.nowPlayingPackage) {
                                "com.google.android.youtube" ->
                                    "https://www.youtube.com/results?search_query=${android.net.Uri.encode(musicQuery)}"
                                "com.google.android.apps.youtube.music" ->
                                    "https://music.youtube.com/search?q=${android.net.Uri.encode(musicQuery)}"
                                "com.apple.android.music" ->
                                    "https://music.apple.com/search?term=${android.net.Uri.encode(musicQuery)}"
                                "deezer.android.app" ->
                                    "https://www.deezer.com/search/${android.net.Uri.encode(musicQuery)}"
                                "com.soundcloud.android" ->
                                    "https://soundcloud.com/search?q=${android.net.Uri.encode(musicQuery)}"
                                "com.amazon.mp3" ->
                                    "https://music.amazon.com/search/${android.net.Uri.encode(musicQuery)}"
                                else ->
                                    "https://open.spotify.com/search/${android.net.Uri.encode(musicQuery)}"
                            }
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(searchUrl))
                            musicCtx.startActivity(intent)
                        }
                    })
            }
        }
    }

    // ── Pinned Message ───────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminCardHeader("Pinned Message", Icons.Filled.PushPin, AdminTone.Primary)
            var pinnedEdit by remember(d.pinnedMessage) { mutableStateOf(d.pinnedMessage) }
            OutlinedTextField(
                value = pinnedEdit,
                onValueChange = { pinnedEdit = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pinned text") },
                trailingIcon = {
                    if (pinnedEdit != d.pinnedMessage)
                        IconButton(onClick = { writeField("afkMessage", pinnedEdit) }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                }
            )
            Divider()
            Text("Presets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.pinnedPresets.forEachIndexed { i, preset ->
                var pe by remember(preset) { mutableStateOf(preset) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = pe, onValueChange = { pe = it },
                        modifier = Modifier.weight(1f), singleLine = true, label = { Text("Preset ${i+1}") })
                    if (pe != preset)
                        IconButton(onClick = { writeField("afkPreset${i+1}", pe) }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                    OutlinedButton(onClick = { writeField("afkMessage", pe) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        Text("Load", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    // ── Cycle ────────────────────────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminCardHeader("Cycle", Icons.Filled.Loop, AdminTone.Primary, trailing = {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    var intEdit by remember(d.cycleIntervalSeconds) { mutableStateOf(d.cycleIntervalSeconds.toString()) }
                    OutlinedTextField(value = intEdit, onValueChange = { intEdit = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.width(72.dp), singleLine = true, label = { Text("Sec") },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                    if (intEdit.isNotBlank() && intEdit.toLongOrNull() != d.cycleIntervalSeconds)
                        IconButton(onClick = { intEdit.toLongOrNull()?.let { writeField("cycleIntervalSeconds", it) } }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                }
            })
            if (d.cycleLinesText.isNotBlank()) {
                var cycleEdit by remember(d.cycleLinesText) { mutableStateOf(d.cycleLinesText) }
                OutlinedTextField(value = cycleEdit, onValueChange = { cycleEdit = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Cycle lines") },
                    minLines = 2, maxLines = 6,
                    trailingIcon = {
                        if (cycleEdit != d.cycleLinesText)
                            IconButton(onClick = { writeField("cycleLinesText", cycleEdit) }) {
                                Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                            }
                    })
            }
            Divider()
            Text("Cycle Presets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.cyclePresets.forEachIndexed { i, preset ->
                var pe by remember(preset) { mutableStateOf(preset) }
                var expanded by remember { mutableStateOf(false) }
                ElevatedCard {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("Preset ${i+1}", style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { expanded = !expanded }) {
                                Icon(
                                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (expanded) "Collapse" else "Expand"
                                )
                            }
                        }
                        if (expanded) {
                            OutlinedTextField(value = pe, onValueChange = { pe = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Cycle lines") },
                                minLines = 2, maxLines = 8)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (pe != preset)
                                    Button(onClick = { writeField("cyclePreset${i+1}", pe) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                        Text("Save", style = MaterialTheme.typography.labelSmall)
                                    }
                                OutlinedButton(onClick = { writeField("cycleLinesText", pe) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Text("Load to active", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            val preview = preset.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "(empty)"
                            Text(preview, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }

    // ── App Info + Targeted Push ────────────────────────────────────
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AdminCardHeader("App & Updates", Icons.Filled.Android, AdminTone.Primary)
            Text("${d.versionName} (${d.versionCode})", fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall)
            Text(d.appId, fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.adminBuild)
                Text("Admin build", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary)

            Divider()

            // Targeted update push
            val ctx = LocalContext.current
            var targetUrl by remember { mutableStateOf("") }
            var targetNotes by remember { mutableStateOf("") }
            var hasTargeted by remember { mutableStateOf(false) }
            var loadedTarget by remember { mutableStateOf(false) }

            // APK upload state
            var tPickedFileName by remember { mutableStateOf("") }
            var tParsedCode by remember { mutableLongStateOf(0L) }
            var tParsedName by remember { mutableStateOf("") }
            var tParseError by remember { mutableStateOf("") }
            var tCachedApkPath by remember { mutableStateOf("") }
            var tUploading by remember { mutableStateOf(false) }
            var tUploadPhase by remember { mutableStateOf("") }
            var tUploadProgress by remember { mutableStateOf(0f) }
            var tUploadDone by remember { mutableStateOf(false) }
            var targetedUploadError by remember { mutableStateOf<String?>(null) }

            val githubPat   = BuildConfig.GITHUB_PAT
            val githubOwner = BuildConfig.GITHUB_OWNER
            val githubRepo  = BuildConfig.GITHUB_REPO
            val tCredsMissing = githubPat.isBlank() || githubOwner.isBlank() || githubRepo.isBlank()

            LaunchedEffect(docId) {
                if (docId.isBlank()) return@LaunchedEffect
                runCatching {
                    val snap = db.collection("releases").document(docId).get(Source.SERVER).await()
                    if (snap.exists()) {
                        val url = snap.getString("downloadUrl").orEmpty()
                        hasTargeted = url.isNotBlank()
                        if (hasTargeted) {
                            targetUrl = url
                            targetNotes = snap.getString("notes").orEmpty()
                        }
                    }
                    loadedTarget = true
                }
            }

            val tFilePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                tParseError = ""; tParsedCode = 0L; tParsedName = ""
                tPickedFileName = ""; tCachedApkPath = ""; tUploadDone = false

                scope.launch {
                    val tmp = copyUriToCache(ctx, uri)
                    if (tmp == null) { tParseError = "Could not read the selected file."; return@launch }
                    tCachedApkPath = tmp.absolutePath

                    tPickedFileName = runCatching {
                        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                            c.moveToFirst()
                            c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
                        }
                    }.getOrNull() ?: "targeted-update.apk"

                    val info = parseApkInfo(ctx, tmp.absolutePath)
                    if (info == null) {
                        tParseError = "Could not read version info.\nMake sure this is a valid APK."
                        return@launch
                    }
                    tParsedCode = info.first
                    tParsedName = info.second
                }
            }

            fun startTargetedUpload() {
                val apkPath = tCachedApkPath
                if (apkPath.isBlank()) return

                scope.launch {
                    tUploading = true; tUploadDone = false; tUploadProgress = 0f; setError(null); targetedUploadError = null

                    runCatching {
                        val apkFile = File(apkPath)
                        val tagName  = "targeted-${docId.take(8)}-${System.currentTimeMillis()}-${(0..999).random()}"
                        val relName  = "Targeted v${tParsedName.ifBlank { tParsedCode.toString() }}"
                        val fileName = "chatbox-vrc-a-targeted-${tParsedName.ifBlank { tParsedCode.toString() }}.apk"
                            .replace(Regex("[^a-zA-Z0-9._-]"), "_")

                        tUploadPhase = "Creating GitHub release..."
                        val release = githubCreateRelease(
                            owner       = githubOwner,
                            repo        = githubRepo,
                            pat         = githubPat,
                            tagName     = tagName,
                            releaseName = relName,
                            body        = targetNotes.trim().ifBlank { "Targeted update for user" }
                        )

                        tUploadPhase = "Uploading APK..."
                        val downloadUrl = githubUploadAsset(
                            uploadUrlTemplate = release.uploadUrl,
                            pat        = githubPat,
                            fileName   = fileName,
                            apkFile    = apkFile,
                            onProgress = { tUploadProgress = it }
                        )

                        tUploadPhase = "Pushing to user..."
                        val releaseData = hashMapOf<String, Any>(
                            "versionCode"       to tParsedCode,
                            "versionName"       to tParsedName.ifBlank { tParsedCode.toString() },
                            "downloadUrl"       to downloadUrl,
                            "requiredMinCode"   to 0L,
                            "notes"             to targetNotes.trim(),
                            "publishedAt"       to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                            "publishedByDevice" to BuildConfig.APPLICATION_ID
                        )
                        FirebaseFirestore.getInstance()
                            .collection("releases")
                            .document(docId)
                            .set(releaseData, SetOptions.merge())
                            .await()

                        targetUrl = downloadUrl
                        hasTargeted = true
                        tUploadDone = true
                        tUploadPhase = ""
                        targetedUploadError = null

                        runCatching { apkFile.delete() }
                        tCachedApkPath = ""; tPickedFileName = ""; tParsedCode = 0L; tParsedName = ""

                    }.onFailure { e ->
                        val msg = e.message ?: "Upload failed"
                        setError(msg)
                        targetedUploadError = msg
                        tUploadPhase = ""
                    }

                    tUploading = false
                }
            }

            if (hasTargeted) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Targeted update active", style = MaterialTheme.typography.labelMedium)
                        Text(targetUrl.take(60), fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                        if (targetNotes.isNotBlank())
                            Text(targetNotes, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            FirebaseFirestore.getInstance()
                                .collection("releases")
                                .document(docId)
                                .delete()
                                .await()
                            hasTargeted = false; targetUrl = ""; targetNotes = ""
                        }.onFailure { e -> setError("Remove failed: ${e.message}") }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Remove Targeted Update", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Text("Push update to this user", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)

                // Option 1: Upload APK file directly
                OutlinedButton(
                    onClick = { tFilePicker.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !tUploading && !tCredsMissing
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (tPickedFileName.isBlank()) "Pick APK file" else tPickedFileName)
                }

                if (tParseError.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(tParseError, modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (tParsedCode > 0L) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Read from APK", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("versionCode = $tParsedCode",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                            Text("versionName = ${tParsedName.ifBlank { "(blank)" }}",
                                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = targetNotes,
                    onValueChange = { targetNotes = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Update notes (optional)") },
                    enabled = !tUploading
                )

                if (tUploading) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(
                            progress = tUploadProgress,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(tUploadPhase.ifBlank { "Working..." },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (tUploadDone) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary)
                            Text("Targeted update pushed! User will see it on next app launch.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // Upload & push button (APK file)
                if (tParsedCode > 0L && tCachedApkPath.isNotBlank()) {
                    Button(
                        onClick = { startTargetedUpload() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !tUploading && !tCredsMissing
                    ) {
                        Icon(Icons.Filled.CloudUpload, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Upload & Push v$tParsedName ($tParsedCode)")
                    }
                }

                if (targetedUploadError != null) {
                    Card(colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(
                            targetedUploadError ?: "",
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Divider()

                // Option 2: Manual URL or fill from latest release
                Text("Or use a URL directly:", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = targetUrl,
                    onValueChange = { targetUrl = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("APK download URL") },
                    placeholder = { Text("https://github.com/...release.apk") }
                )
                OutlinedButton(onClick = {
                    scope.launch {
                        runCatching {
                            val snap = db.collection("releases").document("latest").get().await()
                            targetUrl = snap.getString("downloadUrl").orEmpty()
                            targetNotes = snap.getString("notes").orEmpty().ifBlank {
                                "Update to ${snap.getString("versionName").orEmpty()}"
                            }
                        }.onFailure { setError("Could not load release: ${it.message}") }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Fill from latest release", style = MaterialTheme.typography.labelMedium)
                }
                Button(onClick = {
                    if (targetUrl.trim().isNotBlank()) {
                        scope.launch {
                            runCatching {
                                val releaseData = hashMapOf<String, Any>(
                                    "versionCode"       to Long.MAX_VALUE,
                                    "versionName"       to "Targeted Update",
                                    "downloadUrl"       to targetUrl.trim(),
                                    "requiredMinCode"   to 0L,
                                    "notes"             to targetNotes.trim(),
                                    "publishedAt"       to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                                    "publishedByDevice" to BuildConfig.APPLICATION_ID
                                )
                                FirebaseFirestore.getInstance()
                                    .collection("releases")
                                    .document(docId)
                                    .set(releaseData, SetOptions.merge())
                                    .await()
                                hasTargeted = true
                            }.onFailure { e -> setError("Push failed: ${e.message}") }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth(),
                    enabled = targetUrl.trim().isNotBlank() && tCachedApkPath.isBlank()) {
                    Text("Push URL to This User")
                }
            }
        }
    }

    // ── Moderation flags ─────────────────────────────────────────────
    if (d.warnReason.isNotBlank() || d.banReason.isNotBlank()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Moderation Flags", style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
                if (d.warnReason.isNotBlank())
                    Text("Warn: ${d.warnReason}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                if (d.banReason.isNotBlank())
                    Text("Ban: ${d.banReason}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}
