package com.vrca.admin

import android.content.Context
import com.vrca.vrchat.AvatarGlobalDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The admin catalog maintenance worker, using the dedicated [BotVrchatSession] (so
 * the admin's real account isn't rate-limited).
 *
 * PRIMARY job — verify only REPORTED avatars (scales to a huge catalog): users
 * report suspected-dead avatars (dead thumbnail / failed clone / own avatar set
 * private), the reports persist in the Worker, and the bot fetches + verifies ONLY
 * those. Confirmed-dead → removed; alive → the report is cleared (false positive)
 * and the entry refreshed if its fields changed. Because reports persist, the bot
 * catches up automatically after being offline.
 *
 * SECONDARY — a manual one-time FULL rescan (`requestFullRescan`) walks the whole
 * catalog once, for cleanup (e.g. purging pre-existing dead/private leaks).
 *
 * Admin build only.
 */
object AvatarCatalogSweep {
    @Volatile var running = false; private set
    @Volatile var checked = 0; private set
    @Volatile var refreshed = 0; private set
    @Volatile var removed = 0; private set
    @Volatile var status = "idle"; private set

    private const val PACE_MS = 1200L          // per avatar (bot account)
    private const val BATCH = 20               // ops per /admin push
    private const val POLL_MS = 30_000L        // poll for new reports every 30s
    private const val PASSIVE_BATCH = 30       // oldest avatars per idle pass
    private const val PASSIVE_PAUSE_MS = 5_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var fullRescan = false

    fun progress(): String =
        "${if (running) "running" else "stopped"} · checked=$checked refreshed=$refreshed removed=$removed\n$status"

    fun start(context: Context, adminKey: String) {
        if (running) return
        if (!BotVrchatSession.isLoggedIn(context)) { status = "bot not logged in"; return }
        if (adminKey.isBlank()) { status = "admin key not set"; return }
        running = true; checked = 0; refreshed = 0; removed = 0; status = "starting…"
        val app = context.applicationContext
        job = scope.launch { try { loop(app, adminKey) } finally { running = false; status = "stopped" } }
    }

    fun stop() { running = false; job?.cancel(); status = "stopped" }

    /** Trigger a one-time full-catalog rescan (cleanup) on the next loop pass. */
    fun requestFullRescan() { fullRescan = true }

    private suspend fun loop(context: Context, adminKey: String) {
        while (running && scope.isActive) {
            val hadReports = processReports(context, adminKey)   // PRIMARY: verify reported
            when {
                fullRescan -> { fullRescan = false; fullCatalogPass(context, adminKey) }
                // IDLE (no reports): passively verify the OLDEST-checked avatars.
                !hadReports -> passiveOldestCheck(context, adminKey)
            }
            if (!running) break
            delay(if (hadReports) POLL_MS else PASSIVE_PAUSE_MS)
        }
    }

    /** Verify the PENDING reports (fetched from the Worker) — the scalable path.
     *  Returns true if there were reports to process. */
    private suspend fun processReports(context: Context, adminKey: String): Boolean {
        val reports = AvatarGlobalDb.fetchReports(adminKey)
        if (reports.isEmpty()) return false
        status = "verifying ${reports.size} report(s)"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val clears = mutableListOf<String>()
        for (r in reports) {
            if (!running) break
            if (r.avatarId.isBlank()) { clears.add(r.fileId); continue } // unverifiable → clear
            val chk = BotVrchatSession.checkAvatar(context, r.avatarId)
            checked++
            if (chk == null) { delay(PACE_MS); continue }  // unknown (429/net) → leave for next poll
            if (!chk.alive) {
                removes.add(r.fileId); removed++
            } else {
                clears.add(r.fileId) // alive → false positive
                val cur = AvatarGlobalDb.lookup(r.fileId)
                if (cur != null) {
                    val newFile = chk.fileId ?: cur.fileId
                    val changed = chk.name != cur.name || chk.author != cur.author ||
                        chk.authorId != cur.authorId || chk.platforms != cur.platforms || newFile != cur.fileId
                    if (changed) {
                        if (newFile != cur.fileId) removes.add(cur.fileId)
                        upserts.add(cur.copy(
                            fileId = newFile, name = chk.name, author = chk.author,
                            authorId = chk.authorId, platforms = chk.platforms
                        ))
                        refreshed++
                    }
                }
            }
            if (upserts.size + removes.size + clears.size >= BATCH) {
                AvatarGlobalDb.adminPush(context, adminKey, upserts.toList(), removes.toList(), clears.toList())
                upserts.clear(); removes.clear(); clears.clear()
            }
            delay(PACE_MS)
        }
        if (upserts.isNotEmpty() || removes.isNotEmpty() || clears.isNotEmpty()) {
            AvatarGlobalDb.adminPush(context, adminKey, upserts.toList(), removes.toList(), clears.toList())
        }
        status = "reports done — checked=$checked removed=$removed refreshed=$refreshed"
        return true
    }

    /** IDLE-time passive cleanup: verify the OLDEST-checked avatars (picked LOCALLY
     *  from the cached catalog — no Cloudflare call to select), remove dead ones, and
     *  BATCH-bump their last-checked time (rides the Worker's 10-min flush commit, so
     *  it's ~free). Bounded per pass so it stays incremental. */
    private suspend fun passiveOldestCheck(context: Context, adminKey: String) {
        val entries = AvatarGlobalDb.snapshot().sortedBy { it.checked }.take(PASSIVE_BATCH)
        if (entries.isEmpty()) { status = "catalog empty — idle"; return }
        status = "passive check: oldest ${entries.size}"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val okChecked = mutableListOf<String>()   // alive + unchanged → just bump checked
        for (e in entries) {
            if (!running) break
            val chk = BotVrchatSession.checkAvatar(context, e.avatarId)
            checked++
            if (chk == null) { delay(PACE_MS); continue }  // unknown → don't touch
            if (!chk.alive) {
                removes.add(e.fileId); removed++
            } else {
                val newFile = chk.fileId ?: e.fileId
                val changed = chk.name != e.name || chk.author != e.author ||
                    chk.authorId != e.authorId || chk.platforms != e.platforms || newFile != e.fileId
                if (changed) {
                    if (newFile != e.fileId) removes.add(e.fileId)  // re-key on image change
                    upserts.add(e.copy(fileId = newFile, name = chk.name, author = chk.author,
                        authorId = chk.authorId, platforms = chk.platforms))  // upsert bumps checked
                    refreshed++
                } else {
                    okChecked.add(e.fileId)  // alive + identical → just bump last-checked
                }
            }
            delay(PACE_MS)
        }
        // Advance the LOCAL checked time immediately so the next pass moves forward
        // (the repo catches up on the flush ~10 min later).
        AvatarGlobalDb.markCheckedLocally(okChecked + upserts.map { it.fileId })
        if (removes.isNotEmpty() || upserts.isNotEmpty() || okChecked.isNotEmpty()) {
            AvatarGlobalDb.adminPush(context, adminKey, upserts.toList(), removes.toList(), emptyList(), okChecked.toList())
        }
        status = "passive: checked=$checked removed=$removed refreshed=$refreshed"
    }

    /** One-time full-catalog walk (manual) — cleanup of pre-existing dead/private. */
    private suspend fun fullCatalogPass(context: Context, adminKey: String) {
        AvatarGlobalDb.forceRefresh(context); delay(3000)
        val entries = AvatarGlobalDb.snapshot()
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        for (e in entries) {
            if (!running) break
            status = "full rescan: ${e.name.ifBlank { e.avatarId }}"
            val chk = BotVrchatSession.checkAvatar(context, e.avatarId)
            checked++
            if (chk == null) { delay(PACE_MS); continue }
            if (!chk.alive) { removes.add(e.fileId); removed++ }
            else {
                val newFile = chk.fileId ?: e.fileId
                val changed = chk.name != e.name || chk.author != e.author ||
                    chk.authorId != e.authorId || chk.platforms != e.platforms || newFile != e.fileId
                if (changed) {
                    if (newFile != e.fileId) removes.add(e.fileId)
                    upserts.add(e.copy(fileId = newFile, name = chk.name, author = chk.author,
                        authorId = chk.authorId, platforms = chk.platforms))
                    refreshed++
                }
            }
            if (upserts.size + removes.size >= BATCH) {
                AvatarGlobalDb.adminPush(context, adminKey, upserts.toList(), removes.toList())
                upserts.clear(); removes.clear()
            }
            delay(PACE_MS)
        }
        if (upserts.isNotEmpty() || removes.isNotEmpty()) {
            AvatarGlobalDb.adminPush(context, adminKey, upserts.toList(), removes.toList())
        }
        status = "full rescan done — checked=$checked removed=$removed refreshed=$refreshed"
    }
}
