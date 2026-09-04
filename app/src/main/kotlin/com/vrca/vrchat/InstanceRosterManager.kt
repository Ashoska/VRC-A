package com.vrca.vrchat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.provider.DocumentsContract
import android.util.Log
import com.vrca.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headset instance-roster reader (M2 hookup for the log parser).
 *
 * Tails VRChat's output log on disk, folds it with [VrcLogParser], enriches each
 * member's platform via [VrchatAuthManager.fetchUserInfo], and publishes a
 * [RosterUi] the Home `InstanceRosterPanel` observes.
 *
 * **Log access mirrors the reference companion (see docs/vrc-nexus-teardown.md
 * §4.7)** — it tries, in order:
 *   1. Direct File read of the candidate dirs (needs `MANAGE_EXTERNAL_STORAGE` /
 *      All-files access). VRChat's Quest package is `com.vrchat.VRChatAndroid`
 *      (legacy `com.vrchat.oculus.quest`); it also checks the shared
 *      `Documents/Logs`, `Documents/VRChat`, `VRChat/Logs` folders.
 *   2. **SAF** — a folder the user grants once via `ACTION_OPEN_DOCUMENT_TREE`,
 *      persisted + re-resolved from `getPersistedUriPermissions`. This is the
 *      route when `Android/data/<vrcpkg>` is blocked from the File API (Android
 *      11+), which it is on most Horizon OS versions.
 *
 * **VRChat must have Logging = FULL** (Settings → Debug) or the `OnPlayerJoined`
 * / `Joining` lines this reads are never written — the UI + Settings tell the
 * user this.
 *
 * Started ONLY on the headset build — the public/admin builds never call [start],
 * so `MANAGE_EXTERNAL_STORAGE` lives only in the headset manifest overlay.
 */
object InstanceRosterManager {

    private const val TAG = "InstanceRoster"
    // Log tail cadence. This is LOCAL file I/O (no rate limit), so it's cheap to
    // poll fast — 1s means a world hop shows in presence within ~1s of VRChat
    // flushing the log line (vs the old 10s REST poll). The only downstream pace
    // limit is the Discord RPC's own ~1.5s debounce (Discord rate-limits OP 3).
    private const val POLL_MS = 1_000L
    // FALLBACK-ONLY staleness: used to decide "VRChat closed" only when the fast,
    // reliable OSCQuery "service up" signal isn't available (OSC disabled in
    // VRChat). Longer (5 min) so an AFK user in a quiet instance — whose log can go
    // minutes without a write — isn't falsely flagged closed. When OSC IS enabled,
    // VrcaOscQuery.isServiceUp() detects a real close within ~250ms, so this
    // threshold doesn't gate those users. The file mtime persists across a reboot,
    // so a fresh boot still doesn't re-read a very old log as "in-instance".
    private const val LOG_STALE_MS = 300_000L
    // After the log shows a Disconnected ("good night server") — VRChat quit /
    // headset shutdown / went to background — we force offline INSTANTLY instead of
    // waiting out OSCQuery's ~12s grace. But if OSCQuery then reports VRChat is still
    // up for longer than this window, it was a brief background pause that resumed,
    // so we defer back to OSCQuery and the RETAINED roster fold repopulates. A hair
    // longer than isServiceUp()'s own grace so a real close's service-down lands first.
    private const val SUSPEND_RESUME_CONFIRM_MS = 15_000L
    private const val PREFS = "vrca_roster"
    private const val KEY_TREE_URI = "saf_tree_uri"

    enum class Status {
        /** No access yet — All-files not granted AND no SAF folder chosen. */
        NEEDS_PERMISSION,
        /** Access ok, but no VRChat log found (VRChat not running, or Logging
         *  isn't FULL, or the folder we can read isn't where VRChat writes). */
        NO_LOG,
        /** Log found, but the local player isn't in a world right now. */
        IDLE,
        /** In an instance; [RosterUi.members] is live. */
        LIVE
    }

    data class Member(
        val displayName: String,
        val userId: String?,
        /** "PC" / "Quest" / "iOS" / "" (unknown / not yet resolved). */
        val platform: String,
        val avatarName: String?,
        /** Avatar author (log `Unpacking Avatar (… by …)`). With avatarName this
         *  resolves the exact avatar id for the clone button. */
        val avatarCreator: String? = null,
        /** PRE-RESOLVED clone target (resolved in the background as soon as the
         *  avatar name is known, so the tap is instant): null = still resolving,
         *  "" = no cloneable match found (button greyed out), non-blank = the
         *  avtr_ id ready to select. Re-resolves when they switch avatars. */
        val avatarId: String? = null,
        /** The worn image FILE id [avatarId] resolved FROM (the catalog shard key). Used to report
         *  the EXACT entry for culling when a clone fails 403/404 — the target's live worn thumbnail
         *  may be the fallback by tap time, so it can't be re-derived reliably then. */
        val cloneFileId: String? = null,
        /** In the user's VRChat friends list — sorted near the top, shown yellow. */
        val isFriend: Boolean = false,
        /** The local user themselves — pinned to the very top, shown purple. */
        val isSelf: Boolean = false,
        /** VRChat+ icon / worn-avatar thumbnail for the row (temporary, evicts on
         *  leave). Self reuses the VRChat tab's pic; others come from the API. */
        val profilePicUrl: String = "",
        /** DIAGNOSTIC: the step-by-step clone-resolution trace for this member's CURRENT avatar —
         *  what was tried, what each DB/confirm returned, and the terminal outcome. Surfaced under
         *  the row so the whole resolve process is visible for every user in the instance. */
        val resolveTrace: List<String> = emptyList()
    )

    data class RosterUi(
        val status: Status = Status.NEEDS_PERMISSION,
        val worldName: String? = null,
        val location: String? = null,
        val members: List<Member> = emptyList(),
        /** The log file we're tailing (diagnostics / device confirmation). */
        val logPath: String? = null
    )

    private val _flow = MutableStateFlow(RosterUi())
    val flow: StateFlow<RosterUi> = _flow.asStateFlow()

    // Diagnostics (Settings -> Debug): the current log ref + folded state, so we can
    // see WHY a readable log shows "not in a world" — which lines the parser matched.
    @Volatile private var lastRef: LogRef? = null
    @Volatile private var lastState: VrcLogParser.InstanceState? = null
    // Wall-clock when the current log-suspend (Disconnected) began, 0 when not
    // suspended. Drives the instant-offline-until-resume gate in runLoop.
    @Volatile private var suspendSinceMs: Long = 0L

    /** Human diag: access, the log file being read, the parsed location/world/roster,
     *  and the last ~14 raw log lines tagged with what the parser made of each — so
     *  an unparsed OnPlayerJoined / Joining line's exact format is visible. */
    fun diagString(context: Context): String {
        val sb = StringBuilder()
        sb.append(if (hasAnyAccess(context)) "access=OK" else "access=NONE").append('\n')
        val ref = lastRef
        if (ref == null) { sb.append("no log selected yet"); return sb.toString() }
        sb.append("log=").append(ref.id.substringAfterLast('/')).append("  size=").append(ref.size)
            .append("  age=").append((System.currentTimeMillis() - ref.lastModified) / 1000).append("s\n")
        val st = lastState
        sb.append("location=").append(st?.location ?: "null")
            .append("\nworld=").append(st?.worldName ?: "null")
            .append("  roster=").append(st?.roster?.size ?: 0)
            .append(if (st?.suspended == true) "  suspended=true (good-night seen)" else "")
            .append('\n')
        sb.append("--- last log lines (parse result) ---\n")
        val tail = runCatching { readTail(context, ref, 4000) }.getOrDefault("")
        tail.split('\n').filter { it.isNotBlank() }.takeLast(14).forEach { line ->
            val ev = VrcLogParser.parseLine(line)
            val tag = ev?.let { it::class.simpleName } ?: "—"
            sb.append('[').append(tag).append("] ").append(line.trim().take(78)).append('\n')
        }
        return sb.toString()
    }

    private fun readTail(context: Context, ref: LogRef, bytes: Int): String {
        val buf: ByteArray = if (ref.uri != null) {
            context.contentResolver.openInputStream(ref.uri)?.use { input ->
                readFrom(input, (ref.size - bytes).coerceAtLeast(0))
            } ?: ByteArray(0)
        } else {
            RandomAccessFile(File(ref.id), "r").use { raf ->
                val start = (raf.length() - bytes).coerceAtLeast(0)
                raf.seek(start)
                ByteArray((raf.length() - start).toInt()).also { raf.readFully(it) }
            }
        }
        return String(buf, Charsets.UTF_8)
    }

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Near-instant hop detection: a FileObserver on the direct-file log path wakes
    // the loop the moment VRChat writes a line (no extra permission — it rides the
    // All-files access we already have; uses LESS cpu than polling since it sleeps
    // until a write). The 1s poll stays as the fallback and is the ONLY path for
    // SAF content URIs, which can't be watched.
    private val changeSignal =
        kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    @Volatile private var observer: FileObserver? = null
    @Volatile private var observedPath: String? = null

    @Suppress("DEPRECATION") // String-path ctor for minSdk 26 (File ctor is API 29+)
    private fun ensureObserver(ref: LogRef) {
        if (ref.uri != null) { stopObserver(); return } // SAF -> poll only
        if (observedPath == ref.id && observer != null) return
        stopObserver()
        observer = object : FileObserver(ref.id, FileObserver.MODIFY or FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) { changeSignal.trySend(Unit) }
        }.also { runCatching { it.startWatching() } }
        observedPath = ref.id
    }

    private fun stopObserver() {
        observer?.let { runCatching { it.stopWatching() } }
        observer = null; observedPath = null
    }

    /** Wake on a log write (FileObserver) OR after POLL_MS (fallback / SAF). */
    private suspend fun waitForChange() {
        kotlinx.coroutines.withTimeoutOrNull(POLL_MS) { changeSignal.receive() }
    }

    // userId -> pretty platform. A key is only cached on a SUCCESSFUL lookup
    // (even if the value is "" = genuinely unknown); a FAILED lookup (429 /
    // network) is left uncached so the 2s publish loop re-queues it — the fix
    // for "non-friends never show a platform" (they're fetched after friends,
    // so they're the ones that hit VRChat's rate limit on the initial burst).
    private val platformCache = ConcurrentHashMap<String, String>()
    // Per-user profile-pic URL (from the SAME /users/{id} call as platform).
    // Cleared on leaving an instance so nothing lingers; the images themselves
    // load memory-only (no disk) so they're truly temporary — see the panel.
    private val pfpCache = ConcurrentHashMap<String, String>()
    private val enrichInFlight = ConcurrentHashMap.newKeySet<String>()
    // avatarName last seen per user → detect a SWITCH to refetch that pic 5s later.
    private val lastAvatarByUser = ConcurrentHashMap<String, String>()
    // Last published roster entries (carry joinedAtMs) for the periodic pfp sweep.
    @Volatile private var lastEntries: List<VrcLogParser.RosterEntry> = emptyList()
    // Pre-resolved clone id per user for their CURRENT avatar. avatarIdCache value:
    // "" = resolved, no cloneable match (grey out); non-blank = the avtr_ id.
    // avatarIdResolvedFor tracks which avatarName that id is FOR, so an avatar switch
    // (name change) re-resolves. In-flight guard + single-flight so a big instance
    // resolves top-to-bottom without bursting the DBs/VRChat.
    private val avatarIdCache = ConcurrentHashMap<String, String>()
    private val avatarIdResolvedFor = ConcurrentHashMap<String, String>()
    // The worn image FILE id each cached clone id resolved FROM (the catalog shard key), so a failed
    // clone can report+cull the EXACT entry (the live worn thumbnail may be the fallback by tap time).
    private val avatarCloneFileIdCache = ConcurrentHashMap<String, String>()
    // The resolved avatar's platform compatibility (for the Quest PC-only clone gate).
    private val avatarPlatformsCache = ConcurrentHashMap<String, List<String>>()
    private val avatarResolveInFlight = ConcurrentHashMap.newKeySet<String>()
    // "uid|avatarName" -> when we FIRST attempted to resolve this member's clone id (set for EVERY
    // unresolved member — fallback/loading, transient rate-limit, or no-match-yet). Drives the unified
    // time-based button decision (spinner <40s, grey ≥40s, final grey at the 15-min hard cap) so the
    // button ALWAYS decides within ~40s and never spins longer, and the retry loop re-resolves off it.
    private val avatarLoadingSince = ConcurrentHashMap<String, Long>()
    // Last time a member in the SLOW phase (past the fast window, greyed but still retriable) was
    // re-resolved — so a could-have-been-found avatar (avtrdb recovered, catalog grew) still lights up.
    private val avatarSlowRetryAt = ConcurrentHashMap<String, Long>()
    // Keys (uid|name) whose LAST result was res.loading (worn image is the robot fallback AND no unique
    // name+author yet). These are re-checked on the SHORTER loading cadence and bounded to the loading
    // budget (vs the generic transient retry), and watch BOTH signals — a real thumbnail OR the log's
    // name+author landing (either one resolves).
    private val loadingWatchKeys = ConcurrentHashMap.newKeySet<String>()
    // Members that EXHAUSTED the loading watch (both signals still missing after the budget) — greyed but
    // TAP-REPROBE-able (unlike noMatch/dead, which are definitive and stay tap-disabled). uid -> when it
    // went quiet. Cleared when a new log avatar event / a successful tap re-arms resolution.
    private val loadingGaveUp = ConcurrentHashMap<String, Long>()
    // Per-uid last-attempt inputs so a tap-reprobe can tell whether NEW info actually loaded (worn
    // thumbnail robot->real, or name/author blank->present) BEFORE spending any DB/avtrdb search.
    private val lastResolveFileId = ConcurrentHashMap<String, String>()   // "" = none/unknown observed
    private val lastResolveName = ConcurrentHashMap<String, String>()
    private val lastResolveAuthor = ConcurrentHashMap<String, String>()
    // Per-uid tap-reprobe rate limit (a manual retry is allowed at most once per minute per member).
    private val lastTapReprobeAt = ConcurrentHashMap<String, Long>()
    // Per-uid time the current avatar NAME first appeared/changed — gates the speculative name+author
    // clone until the name is STABLE (a mid-switch stale name must not uniquely match the OLD avatar).
    private val avatarNameSince = ConcurrentHashMap<String, Long>()
    // The instance location we last had caches populated for. A DIRECT hop (A -> B) never passes
    // through "not in world", so the leave-path cache clears don't fire — the old instance's per-user
    // caches AND the shard LRU would leak into the new instance and accumulate across a hopping
    // session. Comparing this to state.location detects the hop and clears on change.
    @Volatile private var lastLocation: String? = null
    private val resolvingAvatars = java.util.concurrent.atomic.AtomicBoolean(false)
    /** True while the roster is actively resolving members' clone ids — that pass hits the
     *  SAME avtrdb/VRCX mirrors the catalog seed search does, so the seed search yields to it
     *  (see AvatarGlobalDb) to keep the instance roster loading fast + avoid DB rate-limits.
     *  Always false on non-headset builds (the roster never starts there). */
    fun isResolvingRoster(): Boolean = resolvingAvatars.get()
    private const val RESOLVE_PACE_MS = 1_000L
    // A fallback-thumbnail member resolves FAST when it can (catalog / name+author / real REST thumb,
    // all within seconds). If it still can't after this short window it's private/personal/unavailable
    // — DECIDE (grey) rather than spin for minutes. Kept just long enough to ride out a brief REST lag.
    private const val LOADING_RESOLVE_WINDOW_MS = 40_000L       // spinner (fast retries) for ~40s, then grey
    private const val LOADING_RERESOLVE_INTERVAL_MS = 12_000L   // fast-phase re-check every 12s
    private const val SLOW_RETRY_INTERVAL_MS = 150_000L         // greyed but still re-checked every ~2.5 min
    private const val LOADING_HARD_CAP_MS = 15 * 60_000L        // TRANSIENT unresolved (rate-limit/catalog): final grey after 15 min
    // LOADING watch (robot worn image + no unique name+author): watch BOTH signals (thumbnail landing OR
    // the log's name+author landing) on a cheaper cadence, bounded to a short budget — real avatars load
    // within seconds, so if NEITHER signal arrives in ~3 min it's stuck/private → go quiet (grey, but the
    // button becomes tap-reprobe-able). This is the rate-limit cut for big instances: no 15-min hammer.
    private const val LOADING_WATCH_INTERVAL_MS = 25_000L       // loading watch: re-check every ~25s
    private const val LOADING_WATCH_BUDGET_MS = 3 * 60_000L     // give up the loading watch after ~3 min → go quiet
    private const val MAX_RECHECK_PER_PASS = 6                  // cap re-checks per retry pass (spread big instances, no burst)
    private const val TAP_REPROBE_MIN_INTERVAL_MS = 60_000L     // a manual retry is allowed at most once/min per member
    private const val NAME_STABLE_MS = 4_000L                   // speculative name+author clone waits for a stable name
    private val enrichAttempts = ConcurrentHashMap<String, Int>()
    // Per-user platform (/users/{id} -> last_platform) is the ONLY source for a
    // NON-friend (friends come free in the bulk friends list). VRChat hard
    // rate-limits per-user calls, so the reference companion (VRC-NEXUS) paces
    // its per-user loops at ~1.3s (its invite gap = W(1300)) and detects 429.
    // Firing the whole roster in a burst = everything after the first couple
    // 429s. So pace ~1.2s and KEEP retrying through 429s (give up only after
    // MANY attempts — a genuinely-dead id caps out instead of looping forever;
    // caches clear on leaving the instance regardless).
    private const val MAX_ENRICH_ATTEMPTS = 40
    private const val ENRICH_PACE_MS = 500L          // gentle gradual fill (top-to-bottom)
    private const val ENRICH_FAIL_BACKOFF_MS = 5000L // back off hard on a 429 (insurance)
    // PFP auto-refresh (a person's pic can change mid-session — VRChat+ custom pic /
    // gallery / avatar pic). Re-fetch 5s after an avatar SWITCH, and sweep everyone
    // present >=10min every 3min, 5s apart (so a full instance doesn't burst VRChat).
    private const val PFP_SWITCH_DELAY_MS = 5_000L
    private const val PFP_CYCLE_MS = 180_000L        // 3 min
    private const val PFP_MIN_PRESENCE_MS = 600_000L // 10 min
    private const val PFP_STAGGER_MS = 5_000L
    // Single-flight guard so platforms resolve as ONE ordered top-to-bottom pass
    // (not several concurrent passes that would race the rate limit).
    private val enriching = java.util.concurrent.atomic.AtomicBoolean(false)
    // Friend-id set (local FriendsCacheStore), refreshed with a short TTL.
    @Volatile private var friendIdsSnapshot: Set<String> = emptySet()
    @Volatile private var friendIdsLoadedAt: Long = 0L

    private fun friendIds(context: Context): Set<String> {
        val now = System.currentTimeMillis()
        if (now - friendIdsLoadedAt > 15_000L) {
            friendIdsSnapshot = try { FriendsCacheStore.load(context).keys } catch (e: Exception) { friendIdsSnapshot }
            friendIdsLoadedAt = now
        }
        return friendIdsSnapshot
    }

    /** Idempotent — safe to call from every Home composition. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch { runLoop(app) }
        startPfpRefreshLoop(app)
        startAvatarLoadingRetryLoop(app)
    }

    /** Re-resolve members still on the VRChat FALLBACK. FAST phase (<40s): every tick, spinner.
     *  SLOW phase (40s–15min): the button is greyed but we keep re-checking every ~2.5 min, so an
     *  avatar that BECOMES findable later (avtrdb recovered from a rate-limit, another user contributed
     *  it, its real thumbnail finally landed) still lights up — it's never permanently lost to a false
     *  40s cutoff. `resolveAvatars` finalises the grey only after [LOADING_HARD_CAP_MS]. */
    private fun startAvatarLoadingRetryLoop(context: Context) {
        scope.launch {
            while (scope.isActive) {
                delay(LOADING_RERESOLVE_INTERVAL_MS)
                if (_flow.value.status != Status.LIVE || avatarLoadingSince.isEmpty()) continue
                val now = System.currentTimeMillis()
                // Which still-unresolved members are DUE for a re-check this tick (no claim yet).
                // Fast phase (<40s) → every tick; past that → a LOADING-watch member on the ~25s cadence,
                // a generic transient member on the ~2.5 min cadence.
                val due = lastEntries.filter { e ->
                    val uid = e.userId ?: return@filter false
                    val name = e.avatarName ?: ""
                    val key = "$uid|$name"
                    val since = avatarLoadingSince[key] ?: return@filter false
                    if (avatarIdResolvedFor[uid] == name) return@filter false
                    val fast = now - since < LOADING_RESOLVE_WINDOW_MS
                    val interval = if (loadingWatchKeys.contains(key)) LOADING_WATCH_INTERVAL_MS else SLOW_RETRY_INTERVAL_MS
                    val slowDue = now - (avatarSlowRetryAt[key] ?: 0L) >= interval
                    fast || slowDue
                }
                if (due.isEmpty()) continue
                // Cap per pass (oldest first-attempt first) + claim only those — so a big instance spreads
                // its re-checks across successive passes instead of bursting. With the 1s inter-member
                // pacing inside resolveAvatars this bounds the VRChat/DB call rate for the whole instance.
                val retry = due.sortedBy { avatarLoadingSince["${it.userId}|${it.avatarName ?: ""}"] ?: 0L }
                    .take(MAX_RECHECK_PER_PASS)
                    .filter { avatarResolveInFlight.add(it.userId!!) }
                if (retry.isEmpty()) continue
                if (resolvingAvatars.compareAndSet(false, true)) {
                    try { resolveAvatars(context, retry) } finally { resolvingAvatars.set(false) }
                } else {
                    retry.forEach { it.userId?.let { u -> avatarResolveInFlight.remove(u) } }
                }
            }
        }
    }

    // ---- access: All-files (File) + SAF (folder grant) -----------------------

    fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    /** Intent to open Android's "All files access" grant for this app. */
    fun allFilesAccessIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )
        else
            Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + context.packageName)
            )

    /** The persisted SAF folder the user granted, if still valid. */
    fun safFolder(context: Context): Uri? {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null) ?: return null
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return null
        val ok = context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission
        }
        return if (ok) uri else null
    }

    /** Persist a folder the user picked via ACTION_OPEN_DOCUMENT_TREE. */
    fun setSafFolder(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE_URI, uri.toString()).apply()
    }

    fun hasAnyAccess(context: Context): Boolean =
        hasStoragePermission() || safFolder(context) != null

    // ---- candidate log locations (direct File) -------------------------------

    /** Candidate VRChat log directories (reference §4.7 order). */
    private fun candidateDirs(): List<File> {
        val base = Environment.getExternalStorageDirectory() // /sdcard
        val pkgs = listOf("com.vrchat.VRChatAndroid", "com.vrchat.oculus.quest")
        val dirs = mutableListOf<File>()
        for (p in pkgs) {
            dirs += File(base, "Android/data/$p/files")
            dirs += File(base, "Android/data/$p/files/VRChat/VRChat")
            dirs += File(base, "Android/data/$p/cache")
        }
        dirs += File(base, "Documents/Logs")
        dirs += File(base, "Documents/VRChat")
        dirs += File(base, "VRChat/Logs")
        dirs += File(base, "VRChat")
        return dirs
    }

    private fun looksLikeLog(name: String): Boolean {
        val n = name.lowercase()
        return (n.startsWith("output_log") || n == "player.log" || n.startsWith("vrchat")) &&
            (n.endsWith(".txt") || n.endsWith(".log"))
    }

    /** VRChat's Quest output log is memory-mapped + size-capped (that's why it's exactly 10 MB
     *  with trailing null padding), so its filesystem MTIME is unreliable — it can stop
     *  updating while VRChat is still writing, and SAF providers report a stale/zero modified
     *  time for a freshly-rotated file. That left the reader STUCK on the OLD log after VRChat
     *  rotated/crashed (a low-memory disconnect → new log file), the "roster stopped, no new
     *  logs" case. VRChat log names embed a sortable timestamp (output_log_YYYYMMDD_HHMMSS, with
     *  or without separators), so pick the newest by NAME timestamp; fall back to mtime when the
     *  name has none (player.log). */
    private fun logStamp(name: String): Long {
        val digits = name.filter { it.isDigit() }
        if (digits.length < 14) return 0L
        val v = digits.take(14).toLongOrNull() ?: return 0L   // FIRST 14 = the YYYYMMDDHHMMSS date
        return if (v >= 20_000_000_000_000L) v else 0L        // sanity: a plausible real timestamp
    }

    /** True if log A (name/mtime) is NEWER than log B — name timestamp first, mtime as tiebreak. */
    private fun isNewerLog(nameA: String, mtimeA: Long, nameB: String, mtimeB: Long): Boolean {
        val sA = logStamp(nameA); val sB = logStamp(nameB)
        return if (sA != sB) sA > sB else mtimeA > mtimeB
    }

    private fun fileName(path: String): String = path.substringAfterLast('/')

    // A log file we can tail, from either access route.
    private data class LogRef(
        val id: String,          // absolute path (File) or uri string (SAF)
        val size: Long,
        val lastModified: Long,
        val uri: Uri?            // non-null => SAF
    )

    private fun findNewestLog(context: Context): LogRef? {
        // Direct File read FIRST — the shared VRChat log dirs (Documents/Logs,
        // etc.) are readable with All-files access, so this is the normal path
        // and a stray SAF folder-pick can't shadow it. SAF is the fallback for
        // File-API-blocked locations (Android/data on Android 11+).
        if (hasStoragePermission()) {
            var best: LogRef? = null
            for (dir in candidateDirs()) {
                val files = try {
                    if (!dir.isDirectory) continue
                    dir.listFiles { f -> f.isFile && looksLikeLog(f.name) } ?: continue
                } catch (e: Exception) {
                    continue
                }
                for (f in files) {
                    if (best == null || isNewerLog(f.name, f.lastModified(), fileName(best!!.id), best!!.lastModified)) {
                        best = LogRef(f.absolutePath, f.length(), f.lastModified(), null)
                    }
                }
            }
            if (best != null) return best
        }
        safFolder(context)?.let { tree -> return newestSaf(context, tree) }
        return null
    }

    /** List the granted SAF tree and return the newest log-looking file. */
    private fun newestSaf(context: Context, tree: Uri): LogRef? {
        return try {
            val cr = context.contentResolver
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            var best: LogRef? = null
            var bestName = ""
            cr.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (!looksLikeLog(name)) continue
                    val modified = c.getLong(2)
                    val size = c.getLong(3)
                    if (best == null || isNewerLog(name, modified, bestName, best!!.lastModified)) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                        best = LogRef(docUri.toString(), size, modified, docUri)
                        bestName = name
                    }
                }
            }
            best
        } catch (e: Exception) {
            Log.w(TAG, "SAF list failed", e); null
        }
    }

    // ---- main loop -----------------------------------------------------------

    private suspend fun runLoop(context: Context) {
        var currentId: String? = null
        var offset = 0L
        var state = VrcLogParser.InstanceState()

        while (scope.isActive) {
            // Log-INDEPENDENT "VRChat closed" detection — the fix for "the Discord
            // RPC stays frozen in-world after the headset is shut down / asleep"
            // (headset-only; mobile presence is REST-driven and already goes
            // offline correctly). OSCQuery reachability is the authoritative "VRChat
            // is open" signal once it has worked (VRChat runs its OSCQuery HTTP
            // server whenever OSC is enabled, which it is for a chatbox user). If
            // it's now DOWN (12s grace built into isServiceUp), VRChat closed or the
            // headset slept (Horizon suspends VRChat), so force presence OFFLINE so
            // the RPC's LAST pushed state is "Not in VRChat" instead of a stale
            // "Join Me - <world>". This runs BEFORE the log-access / no-log bailouts
            // below, so it works even without All-files/SAF log access — the log
            // path only ADDS the roster + faster hop detection when access exists.
            if (com.vrca.osc.VrcaOscQuery.hasEverPolledOk() &&
                !com.vrca.osc.VrcaOscQuery.isServiceUp()) {
                if (BuildConfig.IS_HEADSET_BUILD) {
                    VrchatPipelineState.applyLogPresence(
                        active = false, inWorld = false,
                        location = null, worldName = null, playerCount = 0,
                        seedUserId = "", seedDisplayName = ""
                    )
                }
                stopObserver()
                // Force presence offline (above) for the RPC — but DO NOT wipe the roster/clone/live
                // caches or reset the log position here. This branch fires on a TRANSIENT OSCQuery-down,
                // which on a BACKGROUNDED headset (or a brief headset sleep) is usually just our poll
                // being throttled, not VRChat actually closing. Wiping the caches made every already-
                // resolved clone button grey out and need a full re-resolve on return — the tester's
                // "clonable avatars go grey while the app is in the background" bug. Keeping them (and
                // lastLocation + the log offset) means a resume into the SAME instance restores every
                // button INSTANTLY; a resume into a DIFFERENT instance is caught by publish()'s hop
                // detection (location != lastLocation), which clears them correctly. A genuine leave is
                // still handled by the leave paths in publish().
                _flow.value = RosterUi(status = Status.IDLE)
                delay(POLL_MS); continue
            }
            if (!hasAnyAccess(context)) {
                // No log access -> let REST drive presence on the headset.
                VrchatPipelineState.headsetLogActive = false
                // No log signal available → REST is the authority; don't keep forcing
                // offline (that latch is only for a log-CONFIRMED VRChat close).
                VrchatPipelineState.headsetLogForceOffline = false
                stopObserver()
                _flow.value = _flow.value.copy(status = Status.NEEDS_PERMISSION)
                delay(POLL_MS); continue
            }

            val newest = findNewestLog(context)
            if (newest == null) {
                VrchatPipelineState.headsetLogActive = false
                // No log signal available → REST is the authority; don't keep forcing
                // offline (that latch is only for a log-CONFIRMED VRChat close).
                VrchatPipelineState.headsetLogForceOffline = false
                stopObserver()
                _flow.value = RosterUi(status = Status.NO_LOG)
                currentId = null; offset = 0L; state = VrcLogParser.InstanceState()
                delay(POLL_MS); continue
            }

            // A newer log file appeared -> switch and replay it from the start.
            if (currentId != newest.id) {
                currentId = newest.id; offset = 0L; state = VrcLogParser.InstanceState()
            }
            // Watch the current log for instant wakeups (direct-file only).
            ensureObserver(newest)

            try {
                val len = newest.size
                if (len < offset) { offset = 0L; state = VrcLogParser.InstanceState() } // rotated/truncated
                if (len > offset) {
                    val (nextState, nextOffset) = readDelta(context, newest, offset, state)
                    state = nextState; offset = nextOffset
                }
            } catch (e: Exception) {
                Log.w(TAG, "read failed for ${newest.id}", e)
                VrchatPipelineState.headsetLogActive = false
                // No log signal available → REST is the authority; don't keep forcing
                // offline (that latch is only for a log-CONFIRMED VRChat close).
                VrchatPipelineState.headsetLogForceOffline = false
                stopObserver()
                _flow.value = RosterUi(status = Status.NO_LOG, logPath = newest.id)
                currentId = null; offset = 0L; state = VrcLogParser.InstanceState()
                delay(POLL_MS); continue
            }

            // "VRChat is open": OSC is always enabled for a chatbox app, so once
            // OSCQuery has worked it's AUTHORITATIVE (up = open, aged-out = closed) —
            // immune to an AFK user's quiet log, and it never lets a recent log write
            // mask a real close. Log-mtime freshness is used ONLY as the startup
            // bridge, before OSCQuery has resolved+polled for the first time.
            val serviceUp = if (com.vrca.osc.VrcaOscQuery.hasEverPolledOk())
                com.vrca.osc.VrcaOscQuery.isServiceUp()
            else
                System.currentTimeMillis() - newest.lastModified < LOG_STALE_MS
            // Instant "left the instance" from the log: VRChat writes a networking
            // "good night server" goodbye the moment it disconnects (quit / headset
            // shutdown / extended background), which we see in real time while the app
            // is alive (and on the next boot's replay after a shutdown). Force offline
            // NOW instead of waiting OSCQuery's ~12s grace — but KEEP the fold, and once
            // OSCQuery confirms VRChat is genuinely still up past that grace (a brief
            // pause that resumed) OR real in-instance activity clears state.suspended,
            // the retained roster repopulates. suspendSinceMs deliberately rides its
            // original stamp through a confirmed resume (no re-stamp flicker).
            val nowMs = System.currentTimeMillis()
            if (state.suspended) { if (suspendSinceMs == 0L) suspendSinceMs = nowMs }
            else suspendSinceMs = 0L
            val resumedConfirmed = suspendSinceMs != 0L && serviceUp &&
                nowMs - suspendSinceMs > SUSPEND_RESUME_CONFIRM_MS
            val alive = serviceUp && (suspendSinceMs == 0L || resumedConfirmed)
            // We're CONFIDENT VRChat is closed only when OSCQuery (which has actually
            // polled) reports its HTTP down, or the log wrote "good night server"
            // (suspend). If !alive is merely the log-mtime staleness FALLBACK (OSC
            // disabled + quiet log), we are NOT confident — could be an AFK user still
            // in-world — so presence is handed to REST instead of latched offline.
            val confirmedClosed =
                (com.vrca.osc.VrcaOscQuery.hasEverPolledOk() && !serviceUp) ||
                (suspendSinceMs != 0L && !resumedConfirmed)
            lastRef = newest; lastState = state
            publish(context, state, newest.id, alive, confirmedClosed)
            waitForChange()
        }
    }

    /** Read [ref] from [offset] to EOF, fold whole lines, return the new state +
     *  the offset advanced to the last complete line (a trailing partial line is
     *  left for the next poll). Works over both File and SAF. */
    private fun readDelta(
        context: Context,
        ref: LogRef,
        offset: Long,
        state: VrcLogParser.InstanceState
    ): Pair<VrcLogParser.InstanceState, Long> {
        val buf: ByteArray = if (ref.uri != null) {
            context.contentResolver.openInputStream(ref.uri)?.use { readFrom(it, offset) }
                ?: return state to offset
        } else {
            RandomAccessFile(File(ref.id), "r").use { raf ->
                val len = raf.length()
                if (len <= offset) return state to offset
                raf.seek(offset)
                ByteArray((len - offset).toInt()).also { raf.readFully(it) }
            }
        }
        if (buf.isEmpty()) return state to offset
        val text = String(buf, Charsets.UTF_8)
        val lastNl = text.lastIndexOf('\n')
        if (lastNl < 0) return state to offset // no complete line yet
        val complete = text.substring(0, lastNl)
        val consumed = complete.toByteArray(Charsets.UTF_8).size + 1 // + '\n'
        var s = state
        val now = System.currentTimeMillis()
        for (line in complete.split('\n')) {
            val ev = VrcLogParser.parseLine(line) ?: continue
            // Reliably harvest the local player's OWN avatar on every switch, straight
            // from the log (the OSC /avatar/change path can drop events). onAvatarChanged
            // dedups + is public-only, so this is cheap and safe.
            if (ev is VrcLogParser.LogEvent.OwnAvatar)
                com.vrca.vrchat.AvatarGlobalDb.onAvatarChanged(context, ev.avatarId)
            s = VrcLogParser.apply(s, ev, now)
        }
        return s to (offset + consumed)
    }

    /** Read from [offset] to EOF of a stream (skip isn't guaranteed complete). */
    private fun readFrom(input: InputStream, offset: Long): ByteArray {
        var remaining = offset
        while (remaining > 0) {
            val s = input.skip(remaining)
            if (s <= 0) break
            remaining -= s
        }
        return input.readBytes()
    }

    // ---- publish + platform enrichment --------------------------------------

    private fun publish(
        context: Context, state: VrcLogParser.InstanceState, logPath: String,
        alive: Boolean = true,
        // True when VRChat is CONFIDENTLY closed (OSCQuery down / "good night server"),
        // false when !alive is only the log-mtime staleness fallback (possible AFK).
        // Only a confident close latches presence offline (suppressing stale REST); a
        // staleness fallback hands presence to REST so an AFK user stays in-world.
        confirmedClosed: Boolean = true
    ) {
        // VRChat stopped writing the log (closed / headset shut down). There's no
        // "left" line in that case, so the last state would otherwise show us in an
        // instance forever. Hand presence back to REST (active=false → the log
        // override becomes a passthrough, and the active->inactive edge forces the
        // presence offline in applyLogPresence), and show the roster as not-in-world.
        if (!alive) {
            if (BuildConfig.IS_HEADSET_BUILD) {
                VrchatPipelineState.applyLogPresence(
                    active = false, inWorld = false,
                    location = null, worldName = null, playerCount = 0,
                    seedUserId = "", seedDisplayName = "",
                    confirmedClosed = confirmedClosed
                )
            }
            // Force presence offline (RPC) + show IDLE, but DO NOT wipe the roster/clone caches. While
            // the app is BACKGROUNDED and the user is still in VRChat, `alive` can read false purely
            // because OUR OSCQuery poll got throttled (VRChat is up; we just can't reach it) — wiping
            // here made every resolved clone button grey out and re-resolve on return (the tester's
            // bug). Retaining the caches (and lastLocation) means the moment `alive` recovers into the
            // SAME instance, every button is restored instantly; a real move into a DIFFERENT instance
            // is still cleared by the hop check below (location != lastLocation).
            _flow.value = RosterUi(status = Status.IDLE, worldName = null, location = null, members = emptyList(), logPath = logPath)
            return
        }
        val inWorld = state.location != null && state.roster.isNotEmpty()
        val self = VrchatAuthManager.getStoredUserId(context)

        // HEADSET (plan §9): feed the log-derived location / world / instance /
        // player count into the shared presence, so the Discord RPC + UI read it —
        // instant, log-accurate, and instance HOPS are picked up the moment the log
        // writes them (no REST poll for these fields). Mobile is unaffected.
        if (BuildConfig.IS_HEADSET_BUILD) {
            val selfName = (self?.let { state.roster[it]?.displayName })
                ?: VrchatAuthManager.getStoredDisplayName(context) ?: ""
            VrchatPipelineState.applyLogPresence(
                active = true, inWorld = inWorld,
                location = if (inWorld) state.location else null,
                worldName = if (inWorld) state.worldName else null,
                playerCount = if (inWorld) state.roster.size else 0,
                seedUserId = self ?: "", seedDisplayName = selfName
            )
        }

        // Left the instance -> drop all per-user caches so nothing accumulates
        // in memory across a session (the reader writes NOTHING per-user to disk;
        // this just keeps RAM bounded to the current instance).
        if (!inWorld) {
            // Momentarily not-in-world (empty roster / null location). This can be a genuine leave OR
            // just a transient empty state from a background log re-read / rotation while still in
            // VRChat. Either way DON'T wipe the caches here — retaining them costs one instance's worth
            // of memory (freed on the next hop or on app stop) and means a transient empty that refills
            // to the SAME instance restores every clone button instantly instead of greying them. A
            // genuine move to a DIFFERENT instance is cleared by the hop check below; leaving/hopping
            // away entirely also clears on the next populated instance's hop.
            _flow.value = RosterUi(
                status = Status.IDLE, worldName = state.worldName,
                location = state.location, members = emptyList(), logPath = logPath
            )
            return
        }

        // DIRECT HOP (A -> B) — the log went straight from one populated instance to another without
        // ever passing through "not in world", so the leave-path clear above never fired. Detect it by
        // the location changing and drop the previous instance's per-user caches + shard LRU here, so
        // memory stays bounded to the CURRENT instance and no stale row/clone id carries over. The
        // members below are then rebuilt fresh from this instance's roster (re-enriched/re-resolved).
        if (state.location != lastLocation) {
            clearRosterCaches()
            lastLocation = state.location
        }

        val friends = friendIds(context)
        // Display order: YOU first, then friends, then everyone else; each by
        // join time (top-to-bottom). Enrichment walks this SAME order so
        // platforms fill in top-to-bottom (and friends before non-friends, which
        // is why friends resolve before VRChat's per-user rate limit trips).
        fun rank(e: VrcLogParser.RosterEntry): Int = when {
            e.userId != null && e.userId == self -> 0
            e.userId != null && friends.contains(e.userId) -> 1
            else -> 2
        }
        val ordered = state.roster.values.sortedWith(compareBy({ rank(it) }, { it.joinedAtMs }))
        // Self reuses the VRChat tab's already-loaded pic/platform (no fetch).
        val selfPresence = VrchatPipelineState.presence
        val members = ordered.map { e ->
            val isSelfMember = e.userId != null && e.userId == self
            val plat = when {
                isSelfMember -> VrchatAuthManager.prettyPlatform(selfPresence?.platform ?: "")
                    .ifBlank { e.userId?.let { platformCache[it] } ?: "" }
                else -> e.userId?.let { platformCache[it] } ?: e.platform
            }
            val pfp = if (isSelfMember) (selfPresence?.profilePicUrl ?: "")
                      else e.userId?.let { pfpCache[it] } ?: ""
            // Pre-resolved clone target — the SAME source of truth resolveAvatars publishes with, so the
            // button can't flicker between spinner and grey/blue as the two loops interleave: null =
            // still resolving (spinner, only for the first ~40s), "" = decided grey, non-blank = ready.
            val avaId: String? = if (!isSelfMember && e.userId != null)
                cloneButtonState(e.userId, e.avatarName ?: "") else null
            Member(
                displayName = e.displayName,
                userId = e.userId,
                platform = plat,
                avatarName = e.avatarName,
                avatarCreator = e.avatarCreator,
                avatarId = avaId,
                cloneFileId = if (!isSelfMember) e.userId?.let { avatarCloneFileIdCache[it] } else null,
                isFriend = e.userId != null && friends.contains(e.userId),
                isSelf = isSelfMember,
                profilePicUrl = pfp,
                // Carry the resolver's step trace (kept per-uid in VrchatAuthManager until the roster
                // caches clear) so it survives the ~1s publish rebuild instead of blanking each cycle.
                resolveTrace = if (!isSelfMember) e.userId?.let { VrchatAuthManager.lastResolveTrace(it) } ?: emptyList() else emptyList()
            )
        }
        _flow.value = RosterUi(
            status = Status.LIVE,
            worldName = state.worldName,
            location = state.location,
            members = members,
            logPath = logPath
        )

        // PFP refresh trigger: detect an avatar SWITCH (avatarName changed vs last
        // seen) and re-fetch that person's pic 5s later (the switch may change their
        // avatar-derived pic). The first sighting isn't a switch (initial enrich
        // already fetched it). The periodic sweep (startPfpRefreshLoop) covers
        // VRChat+/gallery changes that happen without an avatar switch.
        lastEntries = ordered
        for (e in ordered) {
            val uid = e.userId ?: continue
            val ava = e.avatarName ?: continue
            val prev = lastAvatarByUser.put(uid, ava)
            // Stamp when this avatar NAME first appeared / changed — the speculative name+author clone
            // (robot worn image) waits until the name has been STABLE for NAME_STABLE_MS so a mid-switch
            // STALE name can't uniquely match (and clone) the player's PREVIOUS avatar (false positive).
            if (prev == null || prev != ava) avatarNameSince[uid] = System.currentTimeMillis()
            if (prev != null && prev != ava) {
                scope.launch { delay(PFP_SWITCH_DELAY_MS); refetchPfp(context, uid) }
            }
        }

        // Pre-resolve clone ids in the background so the button is instant + can grey out when there's
        // no cloneable match. publish only fires the FIRST attempt for a member (no avatarLoadingSince
        // yet); once a member is being tracked, the paced retry loop (startAvatarLoadingRetryLoop) owns
        // its re-resolution. This is critical: publish runs every ~1s (poll + every FileObserver wake),
        // so without this guard an unresolved member re-fired a FULL resolve (GET /users/{id} + GET
        // /avatars/{id} + the DB search) EVERY second — hammering VRChat/avtrdb and causing the very
        // rate-limit failures that kept it unresolved. Single-flight + paced.
        val toResolve = ordered.filter { e ->
            val uid = e.userId ?: return@filter false
            if (uid == self) return@filter false
            val name = e.avatarName ?: ""
            if (avatarIdResolvedFor[uid] == name) return@filter false          // already decided
            if (avatarLoadingSince.containsKey("$uid|$name")) return@filter false // tracked → retry loop owns it
            avatarResolveInFlight.add(uid)
        }
        if (toResolve.isNotEmpty() && resolvingAvatars.compareAndSet(false, true)) {
            scope.launch { try { resolveAvatars(context, toResolve) } finally { resolvingAvatars.set(false) } }
        } else {
            toResolve.forEach { avatarResolveInFlight.remove(it.userId!!) }
        }

        // One ordered enrichment pass at a time (single-flight). Skip self — its
        // platform + pic come from the VRChat tab's presence, no fetch needed.
        val needs = ordered.mapNotNull { it.userId }
            .filter { it != self && !platformCache.containsKey(it) && enrichInFlight.add(it) }
        if (needs.isNotEmpty() && enriching.compareAndSet(false, true)) {
            scope.launch { try { enrichPlatforms(context, needs) } finally { enriching.set(false) } }
        } else if (needs.isNotEmpty()) {
            // A pass is already running; release our claim so the next pass re-queues these.
            needs.forEach { enrichInFlight.remove(it) }
        }
    }

    private suspend fun enrichPlatforms(context: Context, userIds: List<String>) {
        for (id in userIds) {
            val info = try {
                VrchatAuthManager.fetchUserInfo(context, id)
            } catch (e: Exception) {
                null
            }
            enrichInFlight.remove(id)

            if (info != null) {
                // Success — cache the resolved platform (may be "" if the user
                // object genuinely has no platform) + profile pic, republish row.
                val plat = VrchatAuthManager.prettyPlatform(info.platform)
                platformCache[id] = plat
                pfpCache[id] = info.profilePicUrl
                enrichAttempts.remove(id)
                // INSTANT clone id for catalog-backed avatars: the worn file id is in
                // the SAME /users/{id} response as the pic, so a catalog hit resolves
                // the clone id offline right when the pfp loads (no separate DB search).
                val wornFid = Regex("file_[0-9a-fA-F-]{36}").find(info.wornAvatarThumbUrl)?.value
                val avaName = _flow.value.members.firstOrNull { it.userId == id }?.avatarName ?: ""
                var catalogAvatarId: String? = null
                // Name-optional: resolve from the worn file id whether or not the log gave an avatar name
                // (impostor'd players have no name but still a file id). This is the INSTANT catalog-hit
                // shortcut (clone id ready the moment the pfp loads, no separate DB search) — but it MUST
                // apply the SAME guards resolveWornAvatarId does, because it also sets avatarIdResolvedFor,
                // which permanently blocks the guarded resolver from ever running for this member. Missing
                // guards here were the real "clonable → robot, reopening fixes it" bug: a loading player's
                // worn thumbnail is the VRChat fallback (Robot) file, and a bare lookup on that / on the
                // lone Robot catalog entry cached the Robot id as clonable and pinned it.
                //  - isSystemFileId(wornFid): the worn image IS the fallback → player is loading; skip the
                //    shortcut so the guarded resolver's loading path (name+author retry) handles it.
                //  - HIT-only (lookupShardedResult): a transient UNAVAILABLE read must NOT cache/pin — leave
                //    it for the guarded resolver to retry (that path distinguishes HIT/MISS/UNAVAILABLE).
                //  - isSystemAvatar(hit): never offer a resolved-to-fallback entry as clonable.
                if (wornFid != null && !com.vrca.vrchat.AvatarGlobalDb.isSystemFileId(wornFid)) {
                    val shardRes = com.vrca.vrchat.AvatarGlobalDb.lookupShardedResult(context, wornFid)
                    if (shardRes.status == com.vrca.vrchat.AvatarGlobalDb.ShardStatus.HIT) {
                        val hit = shardRes.entry!!
                        if (!com.vrca.vrchat.AvatarGlobalDb.isSystemAvatar(hit.author, hit.avatarId, wornFid)) {
                            // CONFIRM the catalog entry is still wearable BEFORE lighting the button — the
                            // shortcut used to present it unverified, so a since-dead/private catalog entry
                            // showed clickable → robot. One GET, session-cached (shared with the guarded
                            // resolver), so it stays ~instant and never double-charges.
                            val conf = VrchatAuthManager.confirmAvatarLive(context, hit.avatarId)
                            when (conf.live) {
                                true -> if (wornFid in conf.fileIds || conf.fileIds.isEmpty()) {
                                    val plats = conf.platforms.ifEmpty { hit.platforms }
                                    val gated = gateCloneId(hit.avatarId, plats)  // "" if PC-only on Quest
                                    avatarPlatformsCache[id] = plats
                                    avatarIdCache[id] = gated
                                    avatarIdResolvedFor[id] = avaName
                                    if (gated.isNotBlank()) avatarCloneFileIdCache[id] = wornFid else avatarCloneFileIdCache.remove(id)
                                    catalogAvatarId = gated
                                    VrchatAuthManager.putResolveTrace(id, listOf(
                                        "worn image fileId: $wornFid",
                                        "instant enrich shortcut: local catalog HIT ${hit.avatarId}",
                                        "confirmed live" + (if (gated.isBlank()) " but PC-only → greyed on Quest" else ""),
                                        "result: via catalog (enrich shortcut)"))
                                }   // else: worn image no longer matches (stale re-key) → let the guarded resolver find the right one
                                false -> {
                                    // Confirmed dead/private → report + grey DECISIVELY (never a clickable robot).
                                    com.vrca.vrchat.AvatarGlobalDb.report(context, wornFid, hit.avatarId, "dead")
                                    avatarIdCache[id] = ""; avatarIdResolvedFor[id] = avaName
                                    avatarCloneFileIdCache.remove(id); catalogAvatarId = ""
                                    VrchatAuthManager.putResolveTrace(id, listOf(
                                        "worn image fileId: $wornFid",
                                        "instant enrich shortcut: local catalog HIT ${hit.avatarId}",
                                        "confirmed DEAD/private → reported + greyed",
                                        "result: dead"))
                                }
                                null -> { /* transient — don't pin; the guarded resolveAvatars pass retries */ }
                            }
                        }
                    }
                    // MISS/UNAVAILABLE (or a fallback entry) → don't pin; the guarded resolveAvatars pass
                    // (queued from publish because avatarIdResolvedFor is still unset) resolves it properly.
                }
                _flow.value.let { cur ->
                    if (cur.members.any { it.userId == id }) {
                        _flow.value = cur.copy(
                            members = cur.members.map { m ->
                                if (m.userId == id) m.copy(
                                    platform = plat, profilePicUrl = info.profilePicUrl,
                                    avatarId = catalogAvatarId ?: m.avatarId,
                                    cloneFileId = avatarCloneFileIdCache[id] ?: m.cloneFileId,
                                    resolveTrace = VrchatAuthManager.lastResolveTrace(id).ifEmpty { m.resolveTrace }
                                ) else m
                            }
                        )
                    }
                }
                delay(ENRICH_PACE_MS) // pace VRChat REST
            } else {
                // Failure (likely 429) — DON'T cache; the next publish re-queues
                // it so it retries. Give up only after MAX attempts so a
                // permanently-unresolvable id can't loop forever.
                val n = (enrichAttempts[id] ?: 0) + 1
                enrichAttempts[id] = n
                if (n >= MAX_ENRICH_ATTEMPTS) { platformCache[id] = ""; enrichAttempts.remove(id) }
                delay(ENRICH_FAIL_BACKOFF_MS) // back off harder after a failed call
            }
        }
    }

    /** Is the LOCAL user on a Quest/Android device? (headset build, or their own
     *  VRChat presence platform is android) — the case where a PC/iOS-only avatar
     *  can't actually be worn, so its clone button should be greyed out. */
    private fun selfIsQuest(): Boolean {
        if (BuildConfig.IS_HEADSET_BUILD) return true
        val p = VrchatAuthManager.prettyPlatform(VrchatPipelineState.presence?.platform ?: "")
        return p.equals("Quest", ignoreCase = true)
    }

    /** Map a resolved (id, platforms) to the clone-button value: "" = greyed (no
     *  cloneable match, OR a PC/iOS-only avatar while the local user is on Quest —
     *  it can't be worn there), non-blank = the avtr_ id to select. The avatar is
     *  still saved to the catalog regardless (that happens during resolve). */
    /** Drop ALL per-user roster caches + the in-app shard LRU. Called on instance leave AND on a
     *  direct hop (see [lastLocation]) so nothing accumulates in memory across a session — the reader
     *  writes NOTHING per-user to disk; this keeps RAM bounded to the CURRENT instance. The shard LRU
     *  eviction also drops any shard fetched for the previous instance's avatars. */
    private fun clearRosterCaches() {
        platformCache.clear(); pfpCache.clear(); enrichAttempts.clear(); enrichInFlight.clear()
        lastAvatarByUser.clear(); lastEntries = emptyList()
        avatarIdCache.clear(); avatarIdResolvedFor.clear(); avatarCloneFileIdCache.clear()
        avatarPlatformsCache.clear(); avatarResolveInFlight.clear()
        avatarLoadingSince.clear(); avatarSlowRetryAt.clear()
        loadingWatchKeys.clear(); loadingGaveUp.clear()
        lastResolveFileId.clear(); lastResolveName.clear(); lastResolveAuthor.clear(); lastTapReprobeAt.clear()
        avatarNameSince.clear()
        VrchatAuthManager.clearResolveTraces()
        com.vrca.vrchat.AvatarGlobalDb.evictShardCache()
    }

    private fun gateCloneId(id: String?, platforms: List<String>): String {
        if (id.isNullOrBlank()) return ""
        if (selfIsQuest() && platforms.isNotEmpty() &&
            platforms.none { it.equals("Quest", true) || it.equals("android", true) }) return ""
        return id
    }

    /** The SINGLE source of truth for a member's clone-button state, used by BOTH `publish` (runs every
     *  ~1s + on every FileObserver wake) AND `resolveAvatars` (runs on the resolve/retry cadence). They
     *  MUST agree or the button flickers between spinner and grey/blue every second — the reported
     *  "constantly switches between loading and the chose phase" bug, which also made the spinner
     *  reappear past 40s ("loading for longer than 40 seconds"). Returns:
     *    null = still resolving (spinner) — only within the first [LOADING_RESOLVE_WINDOW_MS] (~40s),
     *    ""   = decided grey (no cloneable match / greyed on Quest) — final OR slow-retry phase,
     *    id   = ready (clickable). A member is thus ALWAYS decided (grey or blue) within ~40s and never
     *  shows a spinner longer than that; the slow phase keeps re-resolving underneath but shows grey. */
    private fun cloneButtonState(uid: String, name: String): String? {
        if (avatarIdResolvedFor[uid] == name) return avatarIdCache[uid]   // FINAL: "" grey / id blue
        val since = avatarLoadingSince["$uid|$name"] ?: return null       // not attempted yet → spinner
        return if (System.currentTimeMillis() - since >= LOADING_RESOLVE_WINDOW_MS) "" else null
    }

    enum class RetryResult { REARMED, NOTHING_NEW, RATE_LIMITED, NOT_RETRIABLE }

    /** True if [uid]'s greyed clone button is a LOADING give-up (tap-reprobe-able) vs a definitive
     *  noMatch/dead grey (not). The roster UI uses this to show the manual-retry affordance. */
    fun canRetryClone(uid: String): Boolean = loadingGaveUp.containsKey(uid)

    /**
     * Manual tap-reprobe of a greyed clone button (the escape hatch for a member that EXHAUSTED the
     * loading watch — its avatar loaded after we went quiet). Cheap + guarded so it can't be abused:
     *  - only acts on a LOADING give-up (noMatch/dead stay definitive → NOT_RETRIABLE, no DB/avtrdb),
     *  - at most once per minute per member (RATE_LIMITED otherwise),
     *  - does ONE cheap GET /users/{id} probe and only proceeds to the full DB/avtrdb resolve if NEW
     *    info actually loaded since the last attempt (worn thumbnail robot->real, or the log's name/
     *    author blank->present); nothing new → NOTHING_NEW, zero DB/avtrdb calls.
     * On new info it re-arms resolution (fresh watch) and resolves immediately.
     */
    suspend fun retryClone(context: Context, uid: String): RetryResult = withContext(Dispatchers.IO) {
        if (!loadingGaveUp.containsKey(uid)) return@withContext RetryResult.NOT_RETRIABLE
        val now = System.currentTimeMillis()
        if (now - (lastTapReprobeAt[uid] ?: 0L) < TAP_REPROBE_MIN_INTERVAL_MS) return@withContext RetryResult.RATE_LIMITED
        lastTapReprobeAt[uid] = now
        // ONE cheap /users probe (no DB work) to see if the worn thumbnail finally landed.
        val info = try { VrchatAuthManager.fetchUserInfo(context, uid) } catch (e: Exception) { null }
        val wornFid = info?.wornAvatarThumbUrl?.let { Regex("file_[0-9a-fA-F-]{36}").find(it)?.value } ?: ""
        val entry = lastEntries.firstOrNull { it.userId == uid }
        val curName = entry?.avatarName ?: ""
        val curAuthor = entry?.avatarCreator ?: ""
        // NEW info = a real (non-fallback) thumbnail that differs from last attempt, OR the log's
        // name/author having appeared since (either one is enough to resolve).
        val nowReal = wornFid.isNotBlank() && !com.vrca.vrchat.AvatarGlobalDb.isSystemFileId(wornFid)
        val thumbNewlyUsable = nowReal && wornFid != (lastResolveFileId[uid] ?: "")
        val nameNewlyPresent = (curName.isNotBlank() && lastResolveName[uid].isNullOrBlank()) ||
            (curAuthor.isNotBlank() && lastResolveAuthor[uid].isNullOrBlank())
        if (!thumbNewlyUsable && !nameNewlyPresent) return@withContext RetryResult.NOTHING_NEW
        // Re-arm: clear the give-up + decision so the resolve runs fresh, then resolve immediately.
        loadingGaveUp.remove(uid)
        avatarIdResolvedFor.remove(uid); avatarIdCache.remove(uid)
        avatarLoadingSince["$uid|$curName"] = now
        if (entry != null && resolvingAvatars.compareAndSet(false, true)) {
            try { resolveAvatars(context, listOf(entry)) } finally { resolvingAvatars.set(false) }
        }
        RetryResult.REARMED
    }

    /** Resolve each member's exact clone id in the background (paced, single-flight)
     *  and republish the row with it (or "" when nothing cloneable was found). */
    private suspend fun resolveAvatars(context: Context, list: List<VrcLogParser.RosterEntry>) {
        for (e in list) {
            val uid = e.userId ?: continue
            val name = e.avatarName ?: ""   // name-optional: resolve by file id if blank
            avatarResolveInFlight.remove(uid)
            // The log's avatar name must be STABLE before we'll speculative-clone by name+author on a
            // robot worn image (guards a mid-switch stale name from cloning the previous avatar).
            val nameStable = System.currentTimeMillis() - (avatarNameSince[uid] ?: 0L) >= NAME_STABLE_MS
            val res = try {
                VrchatAuthManager.resolveWornAvatarId(context, uid, name, e.avatarCreator ?: "", nameStable)
            } catch (ex: Exception) { VrchatAuthManager.WornAvatarResult(null) }
            avatarPlatformsCache[uid] = res.platforms
            val id = gateCloneId(res.avatarId, res.platforms)   // "" = greyed (no match or PC-only on Quest)
            val why = AvatarSearch.Diag.lastReason
            AvatarSearch.Diag.record(
                "${e.displayName}: '$name' -> ${res.avatarId ?: "no match"}" +
                    (if (id.isBlank() && !res.avatarId.isNullOrBlank()) " [PC-only, greyed on Quest]" else "") +
                    (if (why.isNotBlank()) " [$why]" else "")
            )
            // A CONFIRMED resolve (res.avatarId != null) is cached and stops re-resolving.
            // A MISS (null) is usually TRANSIENT — the worn-thumbnail fetch or the Cloudflare
            // shard read got rate-limited/timed out — so DON'T cache it as final (that's what
            // made a member "sometimes" cloneable and sometimes stuck: one bad read pinned it
            // broken until they switched avatars). Leave it unresolved so the next pass retries,
            // and only give up (grey) after a few real attempts.
            val tryKey = "$uid|$name"
            // Record this attempt's INPUTS so a manual tap-reprobe can tell whether NEW info later loaded
            // (worn thumbnail robot->real, or name/author blank->present) BEFORE spending any DB search.
            lastResolveFileId[uid] = res.observedFileId ?: ""
            lastResolveName[uid] = name
            lastResolveAuthor[uid] = e.avatarCreator ?: ""
            if (res.avatarId != null) {
                avatarIdCache[uid] = id
                avatarIdResolvedFor[uid] = name
                // Remember the shard key this resolved from so a failed clone reports the exact entry.
                if (id.isNotBlank() && res.fileId != null) avatarCloneFileIdCache[uid] = res.fileId
                else avatarCloneFileIdCache.remove(uid)
                avatarLoadingSince.remove(tryKey)
                avatarSlowRetryAt.remove(tryKey)   // resolved (possibly in the slow phase) → un-grey
                loadingWatchKeys.remove(tryKey); loadingGaveUp.remove(uid)
            } else if (res.dead) {
                // Confirmed dead/private (403/404) → grey DECISIVELY, don't spin/retry (already reported).
                avatarIdCache[uid] = ""; avatarIdResolvedFor[uid] = name
                avatarCloneFileIdCache.remove(uid)
                avatarLoadingSince.remove(tryKey); avatarSlowRetryAt.remove(tryKey)
                loadingWatchKeys.remove(tryKey); loadingGaveUp.remove(uid)   // definitive → tap disabled
            } else if (res.noMatch) {
                // DEFINITIVE no-match reached AFTER querying VRChat/the DBs (private/unindexed avatar,
                // candidates confirmed different, or 0 candidates) → FINAL, grey once and STOP. This is
                // what stops the expensive 6-candidate confirm from re-running every slow-retry for 15
                // min on a private avatar. It re-resolves on its own if they switch avatars (the name
                // key changes → avatarIdResolvedFor no longer matches). Transient nulls (catalog
                // unavailable / 429 / worn-thumb fetch failed) do NOT set noMatch, so they still retry.
                // noMatch is DEFINITIVE (had the info, still denied) → NOT tap-reprobe-able.
                avatarIdCache[uid] = ""; avatarIdResolvedFor[uid] = name
                avatarCloneFileIdCache.remove(uid)
                avatarLoadingSince.remove(tryKey); avatarSlowRetryAt.remove(tryKey)
                loadingWatchKeys.remove(tryKey); loadingGaveUp.remove(uid)
            } else {
                // UNRESOLVED — two kinds, both spinner <40s then grey, retry loop re-resolves underneath:
                //  - res.loading: worn image is the robot fallback AND no unique name+author yet. WATCH
                //    BOTH signals (a real thumbnail OR the log's name+author landing — either resolves)
                //    on the shorter loading cadence, bounded to LOADING_WATCH_BUDGET_MS (~3 min). Real
                //    avatars load within seconds, so if NEITHER arrives it's stuck/private → go QUIET
                //    (grey, but the button becomes tap-reprobe-able via loadingGaveUp). This is the big
                //    rate-limit cut for large instances — no 15-min hammer on a stuck-on-robot player.
                //  - transient (usersFailed / catalog unavailable / no-match-yet): keep the generic
                //    retry to the 15-min hard cap (these genuinely might resolve on the next try).
                if (res.loading) loadingWatchKeys.add(tryKey) else loadingWatchKeys.remove(tryKey)
                loadingGaveUp.remove(uid)   // actively watching again → not a give-up (re-set only on expiry)
                val budget = if (res.loading) LOADING_WATCH_BUDGET_MS else LOADING_HARD_CAP_MS
                val since = avatarLoadingSince.getOrPut(tryKey) { System.currentTimeMillis() }
                val elapsed = System.currentTimeMillis() - since
                when {
                    elapsed >= budget -> {                             // gave up → grey, stop retrying
                        avatarIdCache[uid] = ""; avatarIdResolvedFor[uid] = name
                        avatarLoadingSince.remove(tryKey); avatarSlowRetryAt.remove(tryKey)
                        loadingWatchKeys.remove(tryKey)
                        if (res.loading) loadingGaveUp[uid] = System.currentTimeMillis()  // enable tap-reprobe
                    }
                    elapsed >= LOADING_RESOLVE_WINDOW_MS ->            // grey, keep re-checking on its cadence
                        avatarSlowRetryAt[tryKey] = System.currentTimeMillis()
                    // else fast phase: leave unset → spinner + fast retry (retry loop).
                }
            }
            _flow.value.let { cur ->
                if (cur.members.any { it.userId == uid && it.avatarName == name }) {
                    val shown = cloneButtonState(uid, name)   // SAME source of truth as publish → no flicker
                    val trace = VrchatAuthManager.lastResolveTrace(uid)
                    _flow.value = cur.copy(
                        members = cur.members.map { m ->
                            if (m.userId == uid && m.avatarName == name)
                                m.copy(avatarId = shown, cloneFileId = avatarCloneFileIdCache[uid] ?: m.cloneFileId,
                                    resolveTrace = if (trace.isNotEmpty()) trace else m.resolveTrace)
                            else m
                        }
                    )
                }
            }
            delay(RESOLVE_PACE_MS) // pace the DB + VRChat calls
        }
    }

    /** Re-fetch one member's profile pic and republish their row if it changed. */
    private suspend fun refetchPfp(context: Context, userId: String) {
        val info = try { VrchatAuthManager.fetchUserInfo(context, userId) } catch (e: Exception) { null } ?: return
        val newPfp = info.profilePicUrl
        if (newPfp.isBlank() || newPfp == pfpCache[userId]) return
        pfpCache[userId] = newPfp
        _flow.value.let { cur ->
            if (cur.members.any { it.userId == userId }) {
                _flow.value = cur.copy(
                    members = cur.members.map { m -> if (m.userId == userId) m.copy(profilePicUrl = newPfp) else m }
                )
            }
        }
    }

    /** Periodic pfp sweep for VRChat+/gallery pic changes (no avatar switch fires
     *  for those). Every 3 min, re-fetch every member present >=10 min, one at a
     *  time 5s apart in join order, so a full instance never bursts VRChat's REST. */
    private fun startPfpRefreshLoop(context: Context) {
        scope.launch {
            while (scope.isActive) {
                delay(PFP_CYCLE_MS)
                if (_flow.value.status != Status.LIVE) continue
                val now = System.currentTimeMillis()
                val self = try { VrchatAuthManager.getStoredUserId(context) } catch (e: Exception) { null }
                val due = lastEntries
                    .filter { it.userId != null && it.userId != self && now - it.joinedAtMs >= PFP_MIN_PRESENCE_MS }
                    .sortedBy { it.joinedAtMs }
                for (e in due) {
                    if (!scope.isActive || _flow.value.status != Status.LIVE) break
                    refetchPfp(context, e.userId!!)
                    delay(PFP_STAGGER_MS)
                }
            }
        }
    }
}
