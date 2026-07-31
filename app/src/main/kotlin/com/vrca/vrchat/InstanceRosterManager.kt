package com.vrca.vrchat

import android.content.Context
import android.os.Build
import android.os.Environment
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
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Headset instance-roster reader (M2 hookup for the log parser).
 *
 * Tails VRChat's output log on disk, folds it with [VrcLogParser], enriches each
 * member's platform via [VrchatAuthManager.fetchUserInfo], and publishes a
 * [RosterUi] the Home `InstanceRosterPanel` observes. This is the "connect the
 * logs" glue: file tail -> parse -> fold -> enrich -> StateFlow.
 *
 * Started ONLY on the headset build (`com.gremlin.inc.headset`) — the public /
 * admin builds never call [start], so the `MANAGE_EXTERNAL_STORAGE` permission
 * it needs lives only in the headset manifest overlay.
 *
 * Honest caveat (can't verify without a Quest): the exact VRChat-on-Quest log
 * path, and whether another app's `Android/data` is readable via the File API
 * (vs. requiring SAF), varies by Horizon OS version. This reader scans candidate
 * paths and surfaces a clear [Status] so a real device can confirm the location
 * or tell us it needs SAF instead. The parse/fold half is already device-proven.
 */
object InstanceRosterManager {

    private const val TAG = "InstanceRoster"
    private const val POLL_MS = 2_000L

    enum class Status {
        /** All-files access not granted yet — show the grant button. */
        NEEDS_PERMISSION,
        /** Permission ok, but no VRChat log found yet (VRChat not running?). */
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

    // userId -> pretty platform ("" once fetched-but-unknown so we don't re-hammer).
    private val platformCache = ConcurrentHashMap<String, String>()
    private val enrichInFlight = ConcurrentHashMap.newKeySet<String>()

    /** Idempotent — safe to call from every Home composition. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        scope.launch { runLoop(app) }
    }

    // ---- permission ----------------------------------------------------------

    fun hasStoragePermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager()
        else true

    // ---- log location --------------------------------------------------------

    /** Candidate VRChat log directories on Quest (VRChat writes a Unity player
     *  log to its persistent-data dir; some builds mirror the desktop layout). */
    private fun candidateDirs(): List<File> {
        val base = Environment.getExternalStorageDirectory() // /sdcard
        val pkgs = listOf("com.vrchat.mobile.quest", "com.vrchat.mobile.android")
        val dirs = mutableListOf<File>()
        for (p in pkgs) {
            dirs += File(base, "Android/data/$p/files")
            dirs += File(base, "Android/data/$p/files/VRChat/VRChat")
            dirs += File(base, "Android/data/$p/cache")
        }
        return dirs
    }

    private fun looksLikeLog(name: String): Boolean {
        val n = name.lowercase()
        return (n.startsWith("output_log") || n == "player.log" || n.startsWith("vrchat")) &&
            (n.endsWith(".txt") || n.endsWith(".log"))
    }

    /** Newest log across all candidate dirs, or null if none readable. */
    private fun findNewestLog(): File? {
        var best: File? = null
        for (dir in candidateDirs()) {
            val files = try {
                if (!dir.isDirectory) continue
                dir.listFiles { f -> f.isFile && looksLikeLog(f.name) } ?: continue
            } catch (e: Exception) {
                continue
            }
            for (f in files) {
                if (best == null || f.lastModified() > best!!.lastModified()) best = f
            }
        }
        return best
    }

    // ---- main loop -----------------------------------------------------------

    private suspend fun runLoop(context: Context) {
        var current: File? = null
        var offset = 0L
        var state = VrcLogParser.InstanceState()

        while (scope.isActive) {
            if (!hasStoragePermission()) {
                _flow.value = _flow.value.copy(status = Status.NEEDS_PERMISSION)
                delay(POLL_MS); continue
            }

            val newest = findNewestLog()
            if (newest == null) {
                _flow.value = RosterUi(status = Status.NO_LOG)
                current = null; offset = 0L; state = VrcLogParser.InstanceState()
                delay(POLL_MS); continue
            }

            // A newer log file appeared -> switch and replay it from the start.
            if (current == null || current!!.absolutePath != newest.absolutePath) {
                current = newest; offset = 0L; state = VrcLogParser.InstanceState()
            }

            try {
                val len = newest.length()
                if (len < offset) { offset = 0L; state = VrcLogParser.InstanceState() } // rotated/truncated
                if (len > offset) {
                    val (nextState, nextOffset) = readDelta(newest, offset, state)
                    state = nextState; offset = nextOffset
                }
            } catch (e: Exception) {
                Log.w(TAG, "read failed for ${newest.absolutePath}", e)
                _flow.value = RosterUi(status = Status.NO_LOG, logPath = newest.absolutePath)
                current = null; offset = 0L; state = VrcLogParser.InstanceState()
                delay(POLL_MS); continue
            }

            publish(context, state, newest.absolutePath)
            delay(POLL_MS)
        }
    }

    /** Read [file] from [offset] to EOF, fold whole lines, return the new state +
     *  the offset advanced to the last complete line (a trailing partial line is
     *  left for the next poll). */
    private fun readDelta(
        file: File,
        offset: Long,
        state: VrcLogParser.InstanceState
    ): Pair<VrcLogParser.InstanceState, Long> {
        RandomAccessFile(file, "r").use { raf ->
            val len = raf.length()
            if (len <= offset) return state to offset
            raf.seek(offset)
            val buf = ByteArray((len - offset).toInt())
            raf.readFully(buf)
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
        // Kick off platform lookups for anyone we haven't resolved yet.
        val needs = state.roster.values
            .mapNotNull { it.userId }
            .filter { !platformCache.containsKey(it) && enrichInFlight.add(it) }
        if (needs.isNotEmpty()) scope.launch { enrichPlatforms(context, needs) }
    }

    private suspend fun enrichPlatforms(context: Context, userIds: List<String>) {
        for (id in userIds) {
            try {
                val info = VrchatAuthManager.fetchUserInfo(context, id)
                // Cache even an unknown result ("") so we don't retry every poll;
                // a fresh instance (new roster) doesn't clear the cache, so a user
                // seen again resolves instantly.
                platformCache[id] = info?.let { VrchatAuthManager.prettyPlatform(it.platform) } ?: ""
            } catch (e: Exception) {
                platformCache[id] = ""
            } finally {
                enrichInFlight.remove(id)
            }
            // Re-publish so the row's platform chip fills in as each resolves.
            _flow.value.let { cur ->
                if (cur.members.any { it.userId == id }) {
                    _flow.value = cur.copy(
                        members = cur.members.map { m ->
                            if (m.userId == id) m.copy(platform = platformCache[id] ?: "") else m
                        }
                    )
                }
            }
            delay(300) // pace VRChat REST
        }
    }
}
