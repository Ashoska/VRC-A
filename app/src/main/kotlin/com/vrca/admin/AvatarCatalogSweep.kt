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
    @Volatile var pushError = ""; private set

    /** Wrap adminPush so a REJECTED push (wrong/unset ADMIN_KEY on Cloudflare) is
     *  surfaced instead of silently failing — otherwise the bot detects dead avatars
     *  but nothing is removed. */
    private suspend fun pushOps(
        context: Context, adminKey: String,
        upserts: List<AvatarGlobalDb.Entry>, removes: List<String>,
        clears: List<String> = emptyList(), checked: List<String> = emptyList()
    ): Boolean {
        val ok = AvatarGlobalDb.adminPush(context, adminKey, upserts, removes, clears, checked)
        pushError = if (ok) "" else "PUSH REJECTED — set ADMIN_KEY as a Secret on Cloudflare matching the app key"
        return ok
    }

    /** SAFE fill-only refresh: fills in missing/changed pieces (platforms, author id,
     *  name, image) from a fresh check but NEVER blanks an existing value with an empty
     *  one — so a partial fetch can't harm a good entry. Returns the updated entry if
     *  anything actually changed, else null. */
    private fun safeRefresh(e: AvatarGlobalDb.Entry, chk: BotVrchatSession.AvatarCheck): AvatarGlobalDb.Entry? {
        val newFile = chk.fileId ?: e.fileId
        val newName = chk.name.ifBlank { e.name }
        val newAuthor = chk.author.ifBlank { e.author }
        val newAuthorId = chk.authorId.ifBlank { e.authorId }
        val newPlatforms = if (chk.platforms.isNotEmpty()) chk.platforms else e.platforms
        val changed = newFile != e.fileId || newName != e.name || newAuthor != e.author ||
            newAuthorId != e.authorId || newPlatforms != e.platforms
        return if (changed) e.copy(
            fileId = newFile, name = newName, author = newAuthor,
            authorId = newAuthorId, platforms = newPlatforms
        ) else null
    }

    private const val PACE_MS = 1200L          // per avatar (bot account)
    private const val BATCH = 20               // ops per /admin push
    private const val POLL_MS = 30_000L        // poll for new reports every 30s
    private const val PASSIVE_BATCH = 30       // oldest avatars per idle pass
    private const val PASSIVE_PAUSE_MS = 5_000L
    // Re-verify each avatar at most this often — once the catalog is fresh the passive
    // sweep goes SILENT (no Cloudflare writes) until entries age past this.
    private const val RECHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
    private const val IDLE_SLEEP_MS = 10 * 60_000L  // when everything's fresh
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var fullRescan = false

    fun progress(): String =
        "${if (running) "running" else "stopped"} · checked=$checked refreshed=$refreshed removed=$removed\n$status" +
            (if (pushError.isNotBlank()) "\n$pushError" else "")

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
            // Cheap /health read first; only do the heavier /admin/reports LIST when
            // there are reports to verify (KV list ops have a tight free limit).
            val hadReports = if (AvatarGlobalDb.pendingReportCount() > 0)
                processReports(context, adminKey) else false
            var didPassive = false
            when {
                fullRescan -> { fullRescan = false; fullCatalogPass(context, adminKey) }
                // IDLE: passively verify the oldest STALE avatars.
                !hadReports -> didPassive = passiveOldestCheck(context, adminKey)
            }
            if (!running) break
            // All fresh + no reports -> long idle sleep (no Cloudflare traffic).
            delay(if (hadReports) POLL_MS else if (didPassive) PASSIVE_PAUSE_MS else IDLE_SLEEP_MS)
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
                if (cur != null) safeRefresh(cur, chk)?.let { upd ->
                    if (upd.fileId != cur.fileId) removes.add(cur.fileId) // re-key on image change
                    upserts.add(upd); refreshed++
                }
            }
            if (upserts.size + removes.size + clears.size >= BATCH) {
                pushOps(context, adminKey, upserts.toList(), removes.toList(), clears.toList())
                upserts.clear(); removes.clear(); clears.clear()
            }
            delay(PACE_MS)
        }
        if (upserts.isNotEmpty() || removes.isNotEmpty() || clears.isNotEmpty()) {
            pushOps(context, adminKey, upserts.toList(), removes.toList(), clears.toList())
        }
        status = "reports done — checked=$checked removed=$removed refreshed=$refreshed"
        return true
    }

    /** IDLE-time passive cleanup: verify the OLDEST-checked avatars (picked LOCALLY
     *  from the cached catalog — no Cloudflare call to select), remove dead ones, and
     *  BATCH-bump their last-checked time (rides the Worker's 10-min flush commit, so
     *  it's ~free). Bounded per pass so it stays incremental. */
    private suspend fun passiveOldestCheck(context: Context, adminKey: String): Boolean {
        // Only re-check STALE entries (not verified in RECHECK_INTERVAL_MS). Once the
        // catalog is fresh this is empty -> the sweep idles with no Cloudflare writes.
        val cutoff = System.currentTimeMillis() - RECHECK_INTERVAL_MS
        val entries = AvatarGlobalDb.snapshot()
            .filter { it.checked < cutoff }
            .sortedBy { it.checked }
            .take(PASSIVE_BATCH)
        if (entries.isEmpty()) { status = "all fresh — idle"; return false }
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
                val upd = safeRefresh(e, chk)  // fill-only: never blanks good data
                if (upd != null) {
                    if (upd.fileId != e.fileId) removes.add(e.fileId)  // re-key on image change
                    upserts.add(upd); refreshed++  // upsert bumps checked
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
            pushOps(context, adminKey, upserts.toList(), removes.toList(), emptyList(), okChecked.toList())
        }
        status = "passive: checked=$checked removed=$removed refreshed=$refreshed"
        return true
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
                pushOps(context, adminKey, upserts.toList(), removes.toList())
                upserts.clear(); removes.clear()
            }
            delay(PACE_MS)
        }
        if (upserts.isNotEmpty() || removes.isNotEmpty()) {
            pushOps(context, adminKey, upserts.toList(), removes.toList())
        }
        status = "full rescan done — checked=$checked removed=$removed refreshed=$refreshed"
    }
}
