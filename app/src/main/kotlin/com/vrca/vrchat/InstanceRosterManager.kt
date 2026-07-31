package com.vrca.vrchat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
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
    private const val POLL_MS = 2_000L
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
        val avatarName: String?
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

    // userId -> pretty platform. A key is only cached on a SUCCESSFUL lookup
    // (even if the value is "" = genuinely unknown); a FAILED lookup (429 /
    // network) is left uncached so the 2s publish loop re-queues it — the fix
    // for "non-friends never show a platform" (they're fetched after friends,
    // so they're the ones that hit VRChat's rate limit on the initial burst).
    private val platformCache = ConcurrentHashMap<String, String>()
    private val enrichInFlight = ConcurrentHashMap.newKeySet<String>()
    private val enrichAttempts = ConcurrentHashMap<String, Int>()
    private const val MAX_ENRICH_ATTEMPTS = 6

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
                _flow.value = _flow.value.copy(status = Status.NEEDS_PERMISSION)
                delay(POLL_MS); continue
            }

            val newest = findNewestLog(context)
            if (newest == null) {
                _flow.value = RosterUi(status = Status.NO_LOG)
                currentId = null; offset = 0L; state = VrcLogParser.InstanceState()
                delay(POLL_MS); continue
            }

            // A newer log file appeared -> switch and replay it from the start.
            if (currentId != newest.id) {
                currentId = newest.id; offset = 0L; state = VrcLogParser.InstanceState()
            }

            try {
                val len = newest.size
                if (len < offset) { offset = 0L; state = VrcLogParser.InstanceState() } // rotated/truncated
                if (len > offset) {
                    val (nextState, nextOffset) = readDelta(context, newest, offset, state)
                    state = nextState; offset = nextOffset
                }
            } catch (e: Exception) {
                Log.w(TAG, "read failed for ${newest.id}", e)
                _flow.value = RosterUi(status = Status.NO_LOG, logPath = newest.id)
                currentId = null; offset = 0L; state = VrcLogParser.InstanceState()
                delay(POLL_MS); continue
            }

            publish(context, state, newest.id)
            delay(POLL_MS)
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
        val members = state.roster.values
            .sortedBy { it.joinedAtMs }
            .map { e ->
                val plat = e.userId?.let { platformCache[it] } ?: e.platform
                Member(
                    displayName = e.displayName,
                    userId = e.userId,
                    platform = plat,
                    avatarName = e.avatarName
                )
            }
        _flow.value = RosterUi(
            status = if (inWorld) Status.LIVE else Status.IDLE,
            worldName = state.worldName,
            location = state.location,
            members = members,
            logPath = logPath
        )
        val needs = state.roster.values
            .mapNotNull { it.userId }
            .filter { !platformCache.containsKey(it) && enrichInFlight.add(it) }
        if (needs.isNotEmpty()) scope.launch { enrichPlatforms(context, needs) }
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
                // object genuinely has no platform) and republish that row.
                val plat = VrchatAuthManager.prettyPlatform(info.platform)
                platformCache[id] = plat
                enrichAttempts.remove(id)
                _flow.value.let { cur ->
                    if (cur.members.any { it.userId == id }) {
                        _flow.value = cur.copy(
                            members = cur.members.map { m ->
                                if (m.userId == id) m.copy(platform = plat) else m
                            }
                        )
                    }
                }
                delay(300) // pace VRChat REST
            } else {
                // Failure (likely 429) — DON'T cache; the next publish re-queues
                // it so it retries. Give up only after MAX attempts so a
                // permanently-unresolvable id can't loop forever.
                val n = (enrichAttempts[id] ?: 0) + 1
                enrichAttempts[id] = n
                if (n >= MAX_ENRICH_ATTEMPTS) { platformCache[id] = ""; enrichAttempts.remove(id) }
                delay(800) // back off harder after a failed call
            }
        }
    }
}
