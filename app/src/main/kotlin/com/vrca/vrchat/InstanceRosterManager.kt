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
        /** In the user's VRChat friends list — sorted near the top, shown yellow. */
        val isFriend: Boolean = false,
        /** The local user themselves — pinned to the very top, shown purple. */
        val isSelf: Boolean = false,
        /** VRChat+ icon / worn-avatar thumbnail for the row (temporary, evicts on
         *  leave). Self reuses the VRChat tab's pic; others come from the API. */
        val profilePicUrl: String = ""
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
    // The resolved avatar's platform compatibility (for the Quest PC-only clone gate).
    private val avatarPlatformsCache = ConcurrentHashMap<String, List<String>>()
    private val avatarResolveInFlight = ConcurrentHashMap.newKeySet<String>()
    // Retry count per "uid|avatarName" for an UNCONFIRMED resolve, so a transient miss (rate-limited
    // worn-thumb fetch / Cloudflare shard read) is retried instead of pinned broken until they switch.
    private val avatarResolveTries = ConcurrentHashMap<String, Int>()
    // "uid|avatarName" -> when we FIRST saw this member on the VRChat fallback (Robot) thumbnail.
    // Their real avatar is still loading/processing server-side, so we KEEP re-resolving (spinner,
    // never a final grey) until the real image id lands or LOADING_RESOLVE_WINDOW_MS elapses — so a
    // member who only briefly shows the Robot is never permanently missed.
    private val avatarLoadingSince = ConcurrentHashMap<String, Long>()
    private val resolvingAvatars = java.util.concurrent.atomic.AtomicBoolean(false)
    /** True while the roster is actively resolving members' clone ids — that pass hits the
     *  SAME avtrdb/VRCX mirrors the catalog seed search does, so the seed search yields to it
     *  (see AvatarGlobalDb) to keep the instance roster loading fast + avoid DB rate-limits.
     *  Always false on non-headset builds (the roster never starts there). */
    fun isResolvingRoster(): Boolean = resolvingAvatars.get()
    private const val RESOLVE_PACE_MS = 1_000L
    private const val MAX_RESOLVE_TRIES = 5   // retry a transient miss this many times before greying
    // A fallback-thumbnail member resolves FAST when it can (catalog / name+author / real REST thumb,
    // all within seconds). If it still can't after this short window it's private/personal/unavailable
    // — DECIDE (grey) rather than spin for minutes. Kept just long enough to ride out a brief REST lag.
    private const val LOADING_RESOLVE_WINDOW_MS = 40_000L       // spinner at most ~40s, then a decision
    private const val LOADING_RERESOLVE_INTERVAL_MS = 12_000L   // re-check every 12s (≈3 tries in the window)
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

    /** Re-resolve members who are still showing the VRChat FALLBACK (Robot) — their real avatar is
     *  loading/processing. Runs every [LOADING_RERESOLVE_INTERVAL_MS] independently of log activity
     *  (their avatar can finish loading during a quiet moment), so the clone button lights up the
     *  instant their real image id lands. Bounded: `resolveAvatars` itself gives up after the
     *  [LOADING_RESOLVE_WINDOW_MS] window, so a genuinely-stuck fallback can't spin forever. */
    private fun startAvatarLoadingRetryLoop(context: Context) {
        scope.launch {
            while (scope.isActive) {
                delay(LOADING_RERESOLVE_INTERVAL_MS)
                if (_flow.value.status != Status.LIVE || avatarLoadingSince.isEmpty()) continue
                // Re-resolve the still-loading members from the last roster snapshot (single-flight).
                val retry = lastEntries.filter { e ->
                    val uid = e.userId ?: return@filter false
                    val name = e.avatarName ?: ""
                    avatarLoadingSince.containsKey("$uid|$name") &&
                        avatarIdResolvedFor[uid] != name &&
                        avatarResolveInFlight.add(uid)
                }
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
                platformCache.clear(); pfpCache.clear(); enrichAttempts.clear(); enrichInFlight.clear(); lastAvatarByUser.clear(); lastEntries = emptyList(); avatarIdCache.clear(); avatarIdResolvedFor.clear(); avatarPlatformsCache.clear(); avatarResolveInFlight.clear(); avatarResolveTries.clear(); avatarLoadingSince.clear(); com.vrca.vrchat.AvatarGlobalDb.evictShardCache(); com.vrca.vrchat.AvatarGlobalDb.evictShardCache()
                _flow.value = RosterUi(status = Status.IDLE)
                currentId = null; offset = 0L; state = VrcLogParser.InstanceState()
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
            platformCache.clear(); pfpCache.clear(); enrichAttempts.clear(); enrichInFlight.clear(); lastAvatarByUser.clear(); lastEntries = emptyList(); avatarIdCache.clear(); avatarIdResolvedFor.clear(); avatarPlatformsCache.clear(); avatarResolveInFlight.clear(); avatarResolveTries.clear(); avatarLoadingSince.clear(); com.vrca.vrchat.AvatarGlobalDb.evictShardCache()
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
            platformCache.clear(); pfpCache.clear(); enrichAttempts.clear(); enrichInFlight.clear(); lastAvatarByUser.clear(); lastEntries = emptyList(); avatarIdCache.clear(); avatarIdResolvedFor.clear(); avatarPlatformsCache.clear(); avatarResolveInFlight.clear(); avatarResolveTries.clear(); avatarLoadingSince.clear(); com.vrca.vrchat.AvatarGlobalDb.evictShardCache()
            _flow.value = RosterUi(
                status = Status.IDLE, worldName = state.worldName,
                location = state.location, members = emptyList(), logPath = logPath
            )
            return
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
            // Pre-resolved clone target: null = still resolving (or not applicable),
            // "" = resolved with no cloneable match (gray out), non-blank = ready.
            // Only valid when resolved FOR the current avatar name (an avatar switch
            // invalidates it → null again → the button shows "resolving" and re-runs).
            val avaId: String? = if (!isSelfMember && e.userId != null &&
                avatarIdResolvedFor[e.userId] == (e.avatarName ?: "")) avatarIdCache[e.userId] else null
            Member(
                displayName = e.displayName,
                userId = e.userId,
                platform = plat,
                avatarName = e.avatarName,
                avatarCreator = e.avatarCreator,
                avatarId = avaId,
                isFriend = e.userId != null && friends.contains(e.userId),
                isSelf = isSelfMember,
                profilePicUrl = pfp
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
            if (prev != null && prev != ava) {
                scope.launch { delay(PFP_SWITCH_DELAY_MS); refetchPfp(context, uid) }
            }
        }

        // Pre-resolve clone ids in the background so the button is instant + can grey
        // out when there's no cloneable match. Resolve for anyone (non-self) whose
        // current avatar name isn't resolved yet (a switch changes the name → re-run).
        // Single-flight, paced; the next publish re-queues anyone this pass skipped.
        val toResolve = ordered.filter { e ->
            e.userId != null && e.userId != self &&
                avatarIdResolvedFor[e.userId] != (e.avatarName ?: "") && avatarResolveInFlight.add(e.userId!!)
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
                // Name-optional: resolve from the worn file id whether or not the log
                // gave an avatar name (impostor'd players have no name but still a file id).
                if (wornFid != null) {
                    // lookupSharded reads the R2 catalog by the worn image FILE id (exact) — post-cutover
                    // the in-memory map is empty, so the old lookup() here was DEAD and catalog avatars
                    // never got their instant clone id (they fell to the slower/less-reliable path).
                    com.vrca.vrchat.AvatarGlobalDb.lookupSharded(context, wornFid)?.let { hit ->
                        val gated = gateCloneId(hit.avatarId, hit.platforms)  // "" if PC-only on Quest
                        avatarPlatformsCache[id] = hit.platforms
                        avatarIdCache[id] = gated
                        avatarIdResolvedFor[id] = avaName
                        catalogAvatarId = gated
                    }
                }
                _flow.value.let { cur ->
                    if (cur.members.any { it.userId == id }) {
                        _flow.value = cur.copy(
                            members = cur.members.map { m ->
                                if (m.userId == id) m.copy(
                                    platform = plat, profilePicUrl = info.profilePicUrl,
                                    avatarId = catalogAvatarId ?: m.avatarId
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
    private fun gateCloneId(id: String?, platforms: List<String>): String {
        if (id.isNullOrBlank()) return ""
        if (selfIsQuest() && platforms.isNotEmpty() &&
            platforms.none { it.equals("Quest", true) || it.equals("android", true) }) return ""
        return id
    }

    /** Resolve each member's exact clone id in the background (paced, single-flight)
     *  and republish the row with it (or "" when nothing cloneable was found). */
    private suspend fun resolveAvatars(context: Context, list: List<VrcLogParser.RosterEntry>) {
        for (e in list) {
            val uid = e.userId ?: continue
            val name = e.avatarName ?: ""   // name-optional: resolve by file id if blank
            avatarResolveInFlight.remove(uid)
            val res = try {
                VrchatAuthManager.resolveWornAvatarId(context, uid, name, e.avatarCreator ?: "")
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
            if (res.avatarId != null) {
                avatarIdCache[uid] = id
                avatarIdResolvedFor[uid] = name
                avatarResolveTries.remove(tryKey)
                avatarLoadingSince.remove(tryKey)
            } else if (res.loading) {
                // Their worn thumbnail is the VRChat FALLBACK (Robot) → their real avatar is still
                // loading/processing. Keep it a SPINNER (never a final grey) and keep re-resolving —
                // the moment their real avatar's image id lands, the next pass resolves + enables the
                // clone. Give up (grey) only after the window, so a genuinely-stuck fallback can't
                // spin forever. Does NOT count against the transient MAX_RESOLVE_TRIES.
                val since = avatarLoadingSince.getOrPut(tryKey) { System.currentTimeMillis() }
                if (System.currentTimeMillis() - since >= LOADING_RESOLVE_WINDOW_MS) {
                    avatarIdCache[uid] = ""; avatarIdResolvedFor[uid] = name
                    avatarLoadingSince.remove(tryKey); avatarResolveTries.remove(tryKey)
                }
                // else: leave resolvedFor unset → re-resolved by the loading loop / next publish (spinner).
            } else {
                val tries = (avatarResolveTries[tryKey] ?: 0) + 1
                avatarResolveTries[tryKey] = tries
                if (tries >= MAX_RESOLVE_TRIES) {
                    avatarIdCache[uid] = ""           // genuinely not resolvable → grey out
                    avatarIdResolvedFor[uid] = name
                    avatarResolveTries.remove(tryKey)
                }
                // else: leave resolvedFor unset so this member is re-queued next pass (spinner).
            }
            _flow.value.let { cur ->
                if (cur.members.any { it.userId == uid && it.avatarName == name }) {
                    // Resolved/greyed-final → show the cached value ("" greys the button); otherwise
                    // (transient miss OR still-loading) show null = spinner, so it keeps trying.
                    val shown = if (avatarIdResolvedFor[uid] == name) avatarIdCache[uid] else null
                    _flow.value = cur.copy(
                        members = cur.members.map { m ->
                            if (m.userId == uid && m.avatarName == name) m.copy(avatarId = shown) else m
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
