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
                    if (best == null || f.lastModified() > best!!.lastModified) {
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
                    if (best == null || modified > best!!.lastModified) {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
                        best = LogRef(docUri.toString(), size, modified, docUri)
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
            if (!hasAnyAccess(context)) {
                // No log access -> let REST drive presence on the headset.
                VrchatPipelineState.headsetLogActive = false
                stopObserver()
                _flow.value = _flow.value.copy(status = Status.NEEDS_PERMISSION)
                delay(POLL_MS); continue
            }

            val newest = findNewestLog(context)
            if (newest == null) {
                VrchatPipelineState.headsetLogActive = false
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
                stopObserver()
                _flow.value = RosterUi(status = Status.NO_LOG, logPath = newest.id)
                currentId = null; offset = 0L; state = VrcLogParser.InstanceState()
                delay(POLL_MS); continue
            }

            publish(context, state, newest.id)
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

    private fun publish(context: Context, state: VrcLogParser.InstanceState, logPath: String) {
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
            platformCache.clear(); pfpCache.clear(); enrichAttempts.clear(); enrichInFlight.clear()
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
            Member(
                displayName = e.displayName,
                userId = e.userId,
                platform = plat,
                avatarName = e.avatarName,
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
                _flow.value.let { cur ->
                    if (cur.members.any { it.userId == id }) {
                        _flow.value = cur.copy(
                            members = cur.members.map { m ->
                                if (m.userId == id) m.copy(platform = plat, profilePicUrl = info.profilePicUrl) else m
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
}
