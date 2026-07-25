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
import androidx.compose.material.icons.filled.MeetingRoom
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import com.vrca.richcontent.RichBlock
import com.vrca.richcontent.RichDoc
import com.vrca.richcontent.resolveRichDoc
import com.vrca.app.SubLineCodec
import com.vrca.vrchat.VrchatAuthManager
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
    val offlineAt: Timestamp? = null,
    // Admin kill marker. Within KILL_GRACE_MS of now it forces the row offline
    // even if the user's app reopened and wrote a fresh lastActiveAt — so a kill
    // holds offline briefly instead of instantly bouncing back online.
    val killSignal: Timestamp? = null,
    val versionName: String = ""
    // Profile pictures are NOT stored in Firestore and NOT displayed in the
    // admin UI — AdminAvatar shows the name initial (the on-demand VRChat+
    // fetch never worked reliably and was removed).
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
    val offlineAt: Timestamp? = null,
    val killSignal: Timestamp? = null,
    // Master OSC Start/Stop gate — whether the chatbox is ACTUALLY transmitting to
    // VRChat right now (rides the watched 10s loop / hourly delta; no bonus read).
    val oscSending: Boolean = false
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
 * Derives the instance TYPE (Public / Friends / Friends+ / Group / Invite / Invite+)
 * from the synced `vrchatLocation` string alone — the same markers the public
 * `parseInstanceType` reads (`~friends(` / `~hidden(` / `~group(` / `~private(` /
 * `~canRequestInvite`), so it costs NO reads/writes and no VRChat call. Returns the
 * label + an AdminTone for the chip, or null when the location is blank/redacted
 * ("private") / non-`wrld_` so the caller can hide the chip.
 */
internal fun instanceTypeLabel(location: String): Pair<String, AdminTone>? {
    val loc = location.trim()
    if (loc.isBlank() || !loc.startsWith("wrld_") ||
        loc == "private" || loc == "offline" || loc == "traveling"
    ) return null
    val after = loc.substringAfter(':', "")
    return when {
        after.contains("~friends(") -> "Friends" to AdminTone.Info
        after.contains("~hidden(") -> "Friends+" to AdminTone.Info
        after.contains("~group(") -> "Group" to AdminTone.Primary
        after.contains("~private(") && after.contains("~canRequestInvite") -> "Invite+" to AdminTone.Warn
        after.contains("~private(") -> "Invite" to AdminTone.Warn
        after.isNotBlank() && !after.contains('~') -> "Public" to AdminTone.Success
        else -> null
    }
}

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
 * 10s heartbeat has not yet been observed advancing — the case where the admin opens
 * a user who is ALREADY dead (force-killed / swiped with no offlineAt landing), so the
 * poll never sees a heartbeat to confirm. `AdminRuntime.setSelectedUser` fires the
 * `watcherActiveAt` write IMMEDIATELY on open, so a genuinely-LIVE user's app flips
 * into 10s live-sync and its first heartbeat reaches this poll within ~15-25s; a 45s
 * ramp clears that chain with margin while still flipping a dead user offline fast.
 * (Even if the ramp ever false-flags a live user, it SELF-HEALS the instant the
 * heartbeat advances — the synthesized offlineAt is dropped and the row goes online.)
 * Once the poll HAS seen the heartbeat advance (`loopConfirmed`), this ramp is bypassed
 * and a stopped heartbeat flips offline after just [WATCHED_STALE_WINDOW_MS].
 */
internal const val WATCH_RAMP_MS = 45L * 1000L
// After an admin kill, hold the row offline this long even if the user's app
// reopens and writes a fresh lastActiveAt — so a kill doesn't instantly bounce
// back online. The durable offline (past this window) comes from `offlineAt`,
// which the admin writes in the SAME kill batch (see the Kill App button).
internal const val KILL_GRACE_MS = 7L * 1000L

internal fun isUserOnline(u: UserRow, nowMs: Long = System.currentTimeMillis()): Boolean {
    // Kill grace: a fresh killSignal forces offline regardless of liveness, so a
    // reopen within KILL_GRACE_MS can't flip the row back online. Kill-scoped (not
    // offlineAt) so a normal swipe→instant-reopen still shows online right away.
    val killMs = u.killSignal?.toDate()?.time ?: 0L
    if (killMs > 0L && nowMs - killMs in 0L until KILL_GRACE_MS) return false
    val activeMs = (u.lastActiveAt ?: u.lastSeenAt)?.toDate()?.time ?: return false
    if (nowMs - activeMs >= ONLINE_STALENESS_WINDOW_MS) return false
    // Instant-offline on a clean swipe OR an admin kill: a shutdown/kill marker
    // newer than the last liveness write means the app was closed after that
    // heartbeat. (The kill batch writes offlineAt too, so this stays offline past
    // the grace until the user actually reopens with a newer lastActiveAt.)
    val offlineMs = u.offlineAt?.toDate()?.time ?: 0L
    if (offlineMs > activeMs) return false
    return true
}

/**
 * When the same VRChat account is signed in on multiple phones, each install has
 * its own deviceHash → a separate user doc, so the directory would show the person
 * twice. Returns true if [a] is the better representative to KEEP over [b]: prefer
 * an online row, then the most recently active. Rows with no vrchatUserId are never
 * collapsed (the caller skips them).
 */
internal fun preferRow(a: UserRow, b: UserRow, nowMs: Long): Boolean {
    val aOnline = isUserOnline(a, nowMs)
    val bOnline = isUserOnline(b, nowMs)
    if (aOnline != bOnline) return aOnline
    val aMs = (a.lastActiveAt ?: a.lastSeenAt)?.toDate()?.time ?: 0L
    val bMs = (b.lastActiveAt ?: b.lastSeenAt)?.toDate()?.time ?: 0L
    return aMs > bMs
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
        offlineAt = d.getTimestamp("offlineAt"),
        killSignal = d.getTimestamp("killSignal"),
        versionName = (d.getString("versionName") ?: "").trim()
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
    val ctx = androidx.compose.ui.platform.LocalContext.current

    var selectedDetail        by remember { mutableStateOf<UserDetail?>(null) }
    var selectedDetailLoading by remember { mutableStateOf(false) }

    // Foreground gate for the 10s detail poll below. Compose keeps a composed
    // LaunchedEffect alive when the admin app is merely backgrounded (not swiped),
    // so without this the detail poll kept reading users/{docId} every 10s
    // indefinitely while a detail page was left open in the background — the
    // dominant "reads keep climbing even though no admin is looking" cost. The
    // poll's key includes isForeground, so it cancels on ON_PAUSE and restarts
    // (re-fetching immediately) on ON_RESUME.
    val detailLifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var isForeground by remember { mutableStateOf(true) }
    DisposableEffect(detailLifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> isForeground = true
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> isForeground = false
                else -> {}
            }
        }
        detailLifecycleOwner.lifecycle.addObserver(obs)
        onDispose { detailLifecycleOwner.lifecycle.removeObserver(obs) }
    }

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
            val nowMs = System.currentTimeMillis()
            // Collapse duplicate rows for the same VRChat account (same vrchatUserId
            // on multiple phones = separate device docs). Keep ONE representative per
            // vrchatUserId — preferring online, then freshest — so each person shows
            // once. Rows that never logged into VRChat (blank vrchatUserId) are kept
            // as distinct entries. Original sort order is preserved.
            val best = HashMap<String, UserRow>()
            for (u in users) {
                val vid = u.vrchatUserId.trim()
                if (vid.isBlank()) continue
                val ex = best[vid]
                if (ex == null || preferRow(u, ex, nowMs)) best[vid] = u
            }
            val keepDocIds = best.values.mapTo(HashSet()) { it.docId }
            users.asSequence()
                .filter { it.vrchatUserId.trim().isBlank() || it.docId in keepDocIds }
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
            offlineAt = snap.getTimestamp("offlineAt"),
            killSignal = snap.getTimestamp("killSignal"),
            oscSending = snap.getBoolean("oscSending") ?: false
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
    LaunchedEffect(selectedDocId, isForeground) {
        val docId = selectedDocId
        if (docId.isNullOrBlank()) {
            selectedDetail = null; selectedDetailLoading = false
            return@LaunchedEffect
        }
        // Pause the 10s server poll while the admin app is backgrounded; the last
        // loaded detail stays on screen and the poll resumes on ON_RESUME (this
        // effect re-runs and re-fetches immediately).
        if (!isForeground) return@LaunchedEffect
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
            killSignal   = if (d != null) d.killSignal else rawRow.killSignal,
            isOnlineInApp = d?.isOnlineInApp ?: rawRow.isOnlineInApp
        )
        // Saveable + keyed by user so the scroll position is RESTORED after the media
        // picker recreates the Activity (a plain rememberScrollState reset to the top);
        // switching users still starts at the top.
        val detailScroll = rememberSaveable(rawRow.docId, saver = androidx.compose.foundation.ScrollState.Saver) {
            androidx.compose.foundation.ScrollState(0)
        }
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(detailScroll),
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
                        AdminAvatar(
                            name = primaryLabel,
                            online = isUserOnline(row, nowMs),
                            size = 60
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

                    // Kill App is destructive (force-quits every device on the
                    // account), so it now goes through a confirm like the logout
                    // buttons below — it was previously a single mis-tappable tap.
                    var showKillConfirm by remember(selectedDocId) { mutableStateOf(false) }

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
                            onClick = { showKillConfirm = true },
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

                    if (showKillConfirm) {
                        val killName = row.vrchatDisplayName.ifBlank { row.displayName }.ifBlank { "this user" }
                        com.vrca.ui.common.VrcaConfirmDialog(
                            title = "Force quit the app?",
                            body = "This closes VRC-A on every device on $killName's account right " +
                                "now. It does not ban them, and they can reopen the app whenever " +
                                "they want.",
                            confirmLabel = "Force quit",
                            destructive = true,
                            onConfirm = {
                                showKillConfirm = false
                                scope.launch {
                                    setGlobalLoading(true)
                                    runCatching {
                                        // Account-wide: kill every device on this VRChat account
                                        // (only the currently-open one acts on a fresh killSignal).
                                        // offlineAt rides the SAME write — the DURABLE offline
                                        // marker (isUserOnline: offlineAt > lastActiveAt), written
                                        // by the admin so it can't be lost by the dying app. The
                                        // fresh killSignal separately holds the KILL_GRACE_MS window.
                                        AccountModeration.applyAccountWide(
                                            db, row.docId,
                                            mapOf(
                                                "killSignal" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                                                "offlineAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                                                "isOnlineInApp" to false
                                            )
                                        )
                                    }.onFailure { e -> setError(e.message ?: "Kill failed") }
                                    setGlobalLoading(false)
                                }
                            },
                            onDismiss = { showKillConfirm = false }
                        )
                    }

                    // Remote sign-out: account-wide so every device on this VRChat
                    // account logs out. VRChat sign-out ALSO deletes the single-session
                    // lock (accounts/{vrchatUserId}) so the user can sign in on a new
                    // device — the escape hatch for the hard-deny login.
                    // Both buttons require a confirm dialog: these are destructive
                    // account-wide actions and were too easy to fat-finger (several
                    // users got logged out accidentally).
                    var showLogoutVrcConfirm by remember(selectedDocId) { mutableStateOf(false) }
                    var showLogoutDiscordConfirm by remember(selectedDocId) { mutableStateOf(false) }
                    val targetName = row.vrchatDisplayName.ifBlank { row.displayName }.ifBlank { "this user" }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { showLogoutVrcConfirm = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Log out VRChat") }

                        OutlinedButton(
                            onClick = { showLogoutDiscordConfirm = true },
                            modifier = Modifier.weight(1f)
                        ) { Text("Log out Discord") }
                    }

                    if (showLogoutVrcConfirm) {
                        com.vrca.ui.common.VrcaConfirmDialog(
                            title = "Log out VRChat?",
                            body = "This signs $targetName out of VRChat on every device on " +
                                "their account and frees their single-session lock. They'll " +
                                "have to log back in. This can't be undone.",
                            confirmLabel = "Log out VRChat",
                            destructive = true,
                            onConfirm = {
                                showLogoutVrcConfirm = false
                                scope.launch {
                                    setGlobalLoading(true)
                                    runCatching {
                                        AccountModeration.applyAccountWide(
                                            db, row.docId,
                                            mapOf("logoutVrchatAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())
                                        )
                                        if (row.vrchatUserId.isNotBlank()) {
                                            db.collection("accounts").document(row.vrchatUserId).delete().await()
                                        }
                                    }.onFailure { e -> setError(e.message ?: "VRChat sign-out failed") }
                                    setGlobalLoading(false)
                                }
                            },
                            onDismiss = { showLogoutVrcConfirm = false }
                        )
                    }

                    if (showLogoutDiscordConfirm) {
                        com.vrca.ui.common.VrcaConfirmDialog(
                            title = "Log out Discord?",
                            body = "This disconnects $targetName's Discord Rich Presence on " +
                                "every device on their account. They'll have to reconnect " +
                                "Discord. This can't be undone.",
                            confirmLabel = "Log out Discord",
                            destructive = true,
                            onConfirm = {
                                showLogoutDiscordConfirm = false
                                scope.launch {
                                    setGlobalLoading(true)
                                    runCatching {
                                        AccountModeration.applyAccountWide(
                                            db, row.docId,
                                            mapOf("logoutDiscordAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())
                                        )
                                    }.onFailure { e -> setError(e.message ?: "Discord sign-out failed") }
                                    setGlobalLoading(false)
                                }
                            },
                            onDismiss = { showLogoutDiscordConfirm = false }
                        )
                    }

                    // Invite the ADMIN'S OWN logged-in VRChat account into this
                    // user's current instance (website "Invite Me" behavior via
                    // POST /invite/myself/to/{location}). Works for invite-only /
                    // friends+ / group instances; the user is NOT notified — the
                    // invite lands only on the admin's VRChat. Reads the freshest
                    // location from the 10s detail poll (selectedDetail), since
                    // UserRow doesn't carry vrchatLocation.
                    val adminVrcLoggedIn = remember(selectedDocId) { VrchatAuthManager.isLoggedIn(ctx) }
                    if (adminVrcLoggedIn) {
                        val inviteLoc = d?.vrchatLocation.orEmpty()
                        val canInvite = inviteLoc.startsWith("wrld_")
                        var inviting by remember(selectedDocId) { mutableStateOf(false) }
                        var inviteResult by remember(selectedDocId) { mutableStateOf<String?>(null) }
                        Spacer(Modifier.height(8.dp))
                        val targetVrcId = d?.vrchatUserId.orEmpty()
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    inviting = true; inviteResult = null
                                    // 1. Direct self-invite (works for public / friends /
                                    //    friends+ / group / invite+). Fails on invite-only —
                                    //    the admin has no standing to pull themselves in.
                                    val res = VrchatAuthManager.inviteSelfToInstance(ctx, inviteLoc)
                                    inviteResult = if (res.ok) {
                                        "Invite sent — check your VRChat notifications"
                                    } else {
                                        // 2. Fall back: have the USER's app invite us (they're
                                        //    in the instance, so they can invite anyone). The
                                        //    coordinator does a direct invite, else a hidden
                                        //    friend→invite→unfriend dance. Nothing shows on the
                                        //    user's side.
                                        val adminId = VrchatAuthManager.getStoredUserId(ctx).orEmpty()
                                        if (adminId.isBlank()) {
                                            "Invite failed: your VRChat session isn't ready"
                                        } else {
                                            com.vrca.vrchat.SelfInviteCoordinator.runAdminSide(
                                                ctx, selectedDocId.orEmpty(), adminId, inviteLoc, targetVrcId
                                            )
                                        }
                                    }
                                    inviting = false
                                }
                            },
                            enabled = canInvite && !inviting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (inviting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(Icons.Filled.MeetingRoom, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(if (canInvite) "Invite me to this instance" else "Not in a joinable instance")
                        }
                        inviteResult?.let {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                        online = appOnline
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
                        // Status pills + version + relative time on one wrapping row.
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (appOnline) StatusPill("ONLINE", AdminTone.Primary)
                            if (u.vrchatIsOnline) StatusPill("VRC", AdminTone.Info)
                            if (u.banned) StatusPill("BAN", AdminTone.Error)
                            if (u.warned) StatusPill("WARN", AdminTone.Warn)
                            if (u.versionName.isNotBlank()) {
                                Text(
                                    u.versionName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
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
                    // Live occupancy via the admin's OWN VRChat session (a single
                    // GET /instances/{loc}) — instant and independent of the
                    // user's sync chain, which can lag minutes. Polls every 10s
                    // while the detail is open and falls back to the
                    // Firestore-synced count (e.g. instance not visible to admin).
                    val ctxLive = LocalContext.current
                    var liveCount by remember(d.vrchatLocation) {
                        mutableStateOf<VrchatAuthManager.InstanceCount?>(null)
                    }
                    LaunchedEffect(d.vrchatLocation) {
                        val loc = d.vrchatLocation
                        if (!loc.startsWith("wrld_") || !VrchatAuthManager.isLoggedIn(ctxLive))
                            return@LaunchedEffect
                        while (true) {
                            VrchatAuthManager.fetchInstanceCount(ctxLive, loc)?.let { liveCount = it }
                            delay(10_000L)
                        }
                    }
                    val players = liveCount?.players ?: d.vrchatPlayerCount.toInt()
                    val capacity = liveCount?.capacity ?: d.vrchatCapacity.toInt()
                    val cnt = if (capacity > 0) "$players/$capacity" else "$players"
                    // World line + instance-type chip (Public/Friends/Friends+/Group/
                    // Invite/Invite+), derived locally from the synced location string.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("📍 ${d.vrchatWorld} ($cnt)", style = MaterialTheme.typography.bodySmall)
                        instanceTypeLabel(d.vrchatLocation)?.let { (label, tone) ->
                            StatusPill(label, tone)
                        }
                    }
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
    // Device-only remote Start/Stop: one write per press (oscCommand + oscCommandAt)
    // to THIS device's doc — no account-wide fan-out. The user's existing moderation
    // listener acts on it; the synced `oscSending` reflects the new state within the
    // 10s watched loop (no bonus read/write beyond this single command write).
    fun sendOscCommand(cmd: String) {
        if (docId.isBlank()) return
        db.collection("users").document(docId).set(
            mapOf(
                "oscCommand" to cmd,
                "oscCommandAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).addOnFailureListener { e -> setError("Command failed: ${e.message}") }
    }
    // Optimistic Start/Stop: reflect the press INSTANTLY (the user's app applies it
    // instantly too), then reconcile to the AUTHORITATIVE synced `oscSending` once the
    // user's next liveness write lands — the watched 10s loop / 30s edit debounce /
    // hourly delta all carry `oscSending`, so a genuine desync always self-corrects.
    // The override is cleared when the synced value catches up to the command (success)
    // OR after a 30s timeout (command didn't land → fall back to the real value).
    // Keyed by docId so switching users resets it.
    var optimisticSending by remember(docId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(d.oscSending) {
        if (optimisticSending != null && d.oscSending == optimisticSending) optimisticSending = null
    }
    LaunchedEffect(optimisticSending) {
        if (optimisticSending == null) return@LaunchedEffect
        kotlinx.coroutines.delay(30_000)
        optimisticSending = null
    }
    val shownSending = optimisticSending ?: d.oscSending
    ElevatedCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AdminCardHeader(
                "Live Chatbox Output", Icons.Filled.Chat, AdminTone.Primary,
                trailing = {
                    // Is the chatbox ACTUALLY transmitting to VRChat right now (the
                    // master Start/Stop gate), distinct from the per-feature toggles.
                    StatusPill(
                        if (shownSending) "SENDING" else "IDLE",
                        if (shownSending) AdminTone.Success else AdminTone.Neutral
                    )
                }
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(d.combinedPreviewText.ifBlank { "(nothing sending)" },
                    modifier = Modifier.padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall)
            }
            // Single Start/Stop toggle (like Home): flips optimistically on press,
            // then the synced state reconciles it. Red while sending.
            Button(
                onClick = {
                    val target = !shownSending
                    optimisticSending = target
                    sendOscCommand(if (target) "start" else "stop")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = if (shownSending)
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) {
                Text(
                    if (shownSending) "Stop sending" else "Start sending",
                    color = if (shownSending) MaterialTheme.colorScheme.onError
                        else MaterialTheme.colorScheme.onPrimary
                )
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
            // Pinned may hold up to 3 sub-lines encoded in the field. Show the
            // readable form (rows as " ⏎ ", hidden rows tagged "⊘") and re-encode
            // on save so the admin can see AND edit every row, hidden included.
            val pinnedReadable = SubLineCodec.toAdminText(d.pinnedMessage)
            var pinnedEdit by remember(pinnedReadable) { mutableStateOf(pinnedReadable) }
            OutlinedTextField(
                value = pinnedEdit,
                onValueChange = { pinnedEdit = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Pinned text (⏎ = row, ⊘ = hidden)") },
                trailingIcon = {
                    if (pinnedEdit != pinnedReadable)
                        IconButton(onClick = { writeField("afkMessage", SubLineCodec.fromAdminText(pinnedEdit)) }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                }
            )
            Divider()
            Text("Presets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.pinnedPresets.forEachIndexed { i, preset ->
                val presetReadable = SubLineCodec.toAdminText(preset)
                var pe by remember(presetReadable) { mutableStateOf(presetReadable) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = pe, onValueChange = { pe = it },
                        modifier = Modifier.weight(1f), singleLine = true, label = { Text("Preset ${i+1}") })
                    if (pe != presetReadable)
                        IconButton(onClick = { writeField("afkPreset${i+1}", SubLineCodec.fromAdminText(pe)) }) {
                            Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                        }
                    OutlinedButton(onClick = { writeField("afkMessage", SubLineCodec.fromAdminText(pe)) },
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
                // Each slide can hold up to 3 sub-lines; show them readable (rows
                // as " ⏎ ", hidden as "⊘") and re-encode on save.
                val cycleReadable = SubLineCodec.toAdminCycleText(d.cycleLinesText)
                var cycleEdit by remember(cycleReadable) { mutableStateOf(cycleReadable) }
                OutlinedTextField(value = cycleEdit, onValueChange = { cycleEdit = it },
                    modifier = Modifier.fillMaxWidth(), label = { Text("Cycle lines (⏎ = row, ⊘ = hidden)") },
                    minLines = 2, maxLines = 6,
                    trailingIcon = {
                        if (cycleEdit != cycleReadable)
                            IconButton(onClick = { writeField("cycleLinesText", SubLineCodec.fromAdminCycleText(cycleEdit)) }) {
                                Icon(Icons.Filled.Check, "Save", modifier = Modifier.size(18.dp))
                            }
                    })
            }
            Divider()
            Text("Cycle Presets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            d.cyclePresets.forEachIndexed { i, preset ->
                val presetReadable = SubLineCodec.toAdminCycleText(preset)
                var pe by remember(presetReadable) { mutableStateOf(presetReadable) }
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
                                label = { Text("Cycle lines (⏎ = row, ⊘ = hidden)") },
                                minLines = 2, maxLines = 8)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (pe != presetReadable)
                                    Button(onClick = { writeField("cyclePreset${i+1}", SubLineCodec.fromAdminCycleText(pe)) },
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                        Text("Save", style = MaterialTheme.typography.labelSmall)
                                    }
                                OutlinedButton(onClick = { writeField("cycleLinesText", SubLineCodec.fromAdminCycleText(pe)) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                                    Text("Load to active", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        } else {
                            val preview = presetReadable.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "(empty)"
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
            // targetNotes + targetedBlocks are backed by AdminRuntime (PROCESS lifetime),
            // keyed per user, so they survive the media picker recreating the Activity.
            // rememberSaveable was NOT enough: on recreation `sharedUsers` resets empty,
            // so this whole detail subtree is DISPOSED past Compose's saved-state
            // restoration window and the saved content is dropped (same reason the APK
            // pick uses AdminRuntime). Declared BEFORE the seeding LaunchedEffect.
            val targetKey = "targeted:$docId"
            var targetNotes by remember(docId) { AdminRuntime.editorNotesFor(targetKey) }
            val targetedBlocks = remember(docId) { AdminRuntime.editorBlocksFor(targetKey) }
            var hasTargeted by remember { mutableStateOf(false) }
            var loadedTarget by remember { mutableStateOf(false) }

            // APK picker state is held in AdminRuntime (process lifetime) so it
            // survives the admin Activity being recreated when returning from the
            // system file picker — otherwise the pick "doesn't apply" and the
            // detail view resets. Read it back, scoped to THIS user's docId.
            val pickedApk by AdminRuntime.pickedApkState.collectAsState()
            val picked = pickedApk?.takeIf { it.docId == docId }
            val tPickedFileName = when {
                picked == null -> ""
                picked.parsing -> "Reading APK…"
                else -> picked.fileName
            }
            val tParsedCode = picked?.versionCode ?: 0L
            val tParsedName = picked?.versionName.orEmpty()
            val tParseError = picked?.error.orEmpty()
            val tCachedApkPath = picked?.cachePath.orEmpty()

            // APK upload-action state (local — only relevant while actively pushing)
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
                        if (hasTargeted) targetUrl = url
                        // Seed the EDITABLE fields from the existing targeted release
                        // exactly ONCE per user (so the admin edits its content), never
                        // re-seeding on the picker's recreation which would wipe edits.
                        if (!AdminRuntime.isEditorSeeded(targetKey)) {
                            val notes = snap.getString("notes").orEmpty()
                            if (hasTargeted) targetNotes = notes
                            if (targetedBlocks.isEmpty()) {
                                resolveRichDoc(snap.getString("bodyDoc"), notes)?.blocks?.let {
                                    targetedBlocks.clear(); targetedBlocks.addAll(it)
                                }
                            }
                        }
                    }
                    AdminRuntime.markEditorSeeded(targetKey)
                    loadedTarget = true
                }
            }

            val tFilePicker = rememberLauncherForActivityResult(
                ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri == null) return@rememberLauncherForActivityResult
                tUploadDone = false
                // Copy + parse run on AdminRuntime's process scope, so they
                // complete (and the result persists) even if this Activity is
                // recreated on return from the picker. Use the application
                // context — the Activity may not survive.
                AdminRuntime.ingestPickedApk(ctx.applicationContext, docId, uri)
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

                        val releaseDoc = RichDoc(blocks = targetedBlocks.toList())
                        val bodyDocJson = if (releaseDoc.blocks.isEmpty()) "" else releaseDoc.toJson()
                        val notesPlain = targetNotes.trim().ifBlank { releaseDoc.toPlainText() }

                        tUploadPhase = "Creating GitHub release..."
                        val release = githubCreateRelease(
                            owner       = githubOwner,
                            repo        = githubRepo,
                            pat         = githubPat,
                            tagName     = tagName,
                            releaseName = relName,
                            body        = notesPlain.ifBlank { "Targeted update for user" }
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
                            "notes"             to notesPlain,
                            "bodyDoc"           to bodyDocJson,
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

                        // Clears the picked-APK holder and deletes the cached file.
                        AdminRuntime.clearPickedApk(docId)
                        AdminRuntime.clearEditor(targetKey)  // blocks + notes + seeded flag

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
                    label = { Text("Plain notes (GitHub + legacy clients)") },
                    enabled = !tUploading
                )

                Text(
                    "Rich in-app notes (optional) — shown in the update popup and What's New",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                RichDocEditor(blocks = targetedBlocks, githubPat = githubPat)

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
