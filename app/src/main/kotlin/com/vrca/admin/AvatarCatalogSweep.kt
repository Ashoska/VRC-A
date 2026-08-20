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
 * The admin catalog maintenance workers, using dedicated [BotVrchatSession] slots (so
 * the admin's real account isn't rate-limited). Up to FOUR bots run at once, each an
 * independent VRChat session, with FOUR named roles whose live counters are shown
 * per-role in the admin Bots tab:
 *
 *  - **REPORTS**  — verify only PENDING reports (dead/renamed). Confirmed-dead removed,
 *                   false positives cleared + refreshed. Scales to a huge catalog.
 *  - **FILL**     — first-fill NEW/incomplete entries (name, author, platforms, bio)
 *                   via `GET /avatars/{id}` — the info Quest devices can't read. Marks
 *                   the entry `filled=true`.
 *  - **LIVENESS A/B** — passive oldest-checked-first dead/refresh sweep, PARTITIONED by
 *                   a stable hash of the file id (A = partition 0, B = partition 1) so
 *                   the two liveness bots NEVER check the same avatar.
 *
 * Roles are assigned to whichever bot slots are logged in (1..4). With 4 bots, one
 * role each runs in parallel; with fewer, a slot round-robins several roles. The
 * liveness partition guarantees no double-checking regardless of assignment.
 *
 * A **full blitz** ([requestFullBlitz]) widens the fill + liveness scope for a bounded
 * window so all bots catch up the whole catalog (fill everything from before + dead
 * checks). Admin build only.
 */
object AvatarCatalogSweep {

    enum class Role(val label: String) { REPORTS("Reports"), FILL("Fill"), LIVENESS_A("Liveness A"), LIVENESS_B("Liveness B") }

    class Progress {
        @Volatile var running = false
        @Volatile var slot = -1
        @Volatile var checked = 0
        @Volatile var refreshed = 0
        @Volatile var removed = 0
        @Volatile var filled = 0
        @Volatile var status = "idle"
    }

    private val progress = Role.values().associateWith { Progress() }
    fun progressOf(role: Role): Progress = progress.getValue(role)

    @Volatile var running = false; private set
    @Volatile var pushError = ""; private set
    @Volatile private var blitzUntilMs = 0L
    fun blitzActive(): Boolean = System.currentTimeMillis() < blitzUntilMs

    private const val PACE_MS = 1200L            // per avatar per bot
    // ONE /admin push per pass (= one KV write). BATCH is set ABOVE the per-pass sizes
    // so the mid-pass push never fires — a whole pass's ops go up in a single POST,
    // keeping us well under Cloudflare's free KV write budget.
    private const val BATCH = 500                // ops per /admin push (effectively one/pass)
    private const val FILL_BATCH = 40            // entries per fill pass
    private const val LIVENESS_BATCH = 40        // entries per liveness pass
    private const val RECHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days
    private const val BLITZ_RECHECK_MS = 60L * 60 * 1000               // 1 h while blitzing
    private const val BLITZ_WINDOW_MS = 30L * 60 * 1000                // blitz lasts 30 min per press
    private const val IDLE_SLEEP_MS = 5 * 60_000L
    private const val ACTIVE_PAUSE_MS = 2_000L
    private const val CATALOG_REFRESH_MS = 5 * 60_000L   // keep the admin catalog fresh for FILL

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    /** A short human summary of which bot slot runs which roles (for the UI). */
    @Volatile var assignmentLabel = ""; private set

    fun start(context: Context, adminKey: String) {
        if (running) return
        val app = context.applicationContext
        val live = (0 until BotVrchatSession.SLOTS).filter { BotVrchatSession.isLoggedIn(app, it) }
        if (live.isEmpty()) { progress.values.forEach { it.status = "no bot logged in" }; return }
        if (adminKey.isBlank()) { progress.values.forEach { it.status = "admin key not set" }; return }
        // Assign roles to slots: one each when 4 bots, otherwise share the earliest slots.
        val roleSlot = mapOf(
            Role.REPORTS to live[0],
            Role.FILL to (live.getOrNull(1) ?: live[0]),
            Role.LIVENESS_A to (live.getOrNull(2) ?: live[0]),
            Role.LIVENESS_B to (live.getOrNull(3) ?: live.getOrNull(1) ?: live[0]),
        )
        Role.values().forEach { r ->
            progress.getValue(r).apply { running = true; slot = roleSlot.getValue(r); checked = 0; refreshed = 0; removed = 0; filled = 0; status = "starting…" }
        }
        assignmentLabel = live.joinToString("  ·  ") { s ->
            val roles = roleSlot.filterValues { it == s }.keys.joinToString(", ") { it.label }
            "bot ${s + 1}: $roles"
        }
        running = true; pushError = ""
        // One coroutine PER SLOT; it round-robins the roles assigned to that slot so a
        // single VRChat session never fires two roles in parallel (rate-limit safety).
        val bySlot = roleSlot.entries.groupBy({ it.value }, { it.key })
        for ((slot, roles) in bySlot) {
            jobs += scope.launch {
                try { slotLoop(app, adminKey, slot, roles) }
                finally { roles.forEach { progress.getValue(it).running = false; progress.getValue(it).status = "stopped" } }
            }
        }
        // Keep the admin's local catalog FRESH so NEW ids contributed by user devices
        // (which land unfilled) reach the FILL bot promptly, not only on the 30-min
        // refresh. Cheap ETag pull; a 304 costs ~nothing.
        jobs += scope.launch {
            while (running && scope.isActive) {
                AvatarGlobalDb.forceRefresh(app)
                delay(CATALOG_REFRESH_MS)
            }
        }
    }

    fun stop() {
        running = false
        jobs.forEach { it.cancel() }; jobs.clear()
        progress.values.forEach { it.running = false; it.status = "stopped" }
    }

    /** Kick a bounded full-catalog blitz: for the next window, FILL targets every
     *  incomplete entry and LIVENESS re-checks anything older than 1h — so all bots
     *  catch up the whole catalog (bios + dead checks) from before. Re-press to extend. */
    fun requestFullBlitz() { blitzUntilMs = System.currentTimeMillis() + BLITZ_WINDOW_MS }

    fun progressLine(role: Role): String {
        val p = progress.getValue(role)
        val where = if (p.slot >= 0) "bot ${p.slot + 1}" else "-"
        return "${role.label} ($where): checked=${p.checked} refreshed=${p.refreshed} removed=${p.removed} filled=${p.filled}\n${p.status}"
    }

    /** A per-role snapshot for the admin UI: how much is QUEUED (backlog) for this
     *  role right now + what it's processed so far — so the admin can see whether a
     *  type is falling behind (add another bot) and watch the numbers climb. */
    data class RoleView(
        val role: Role, val bot: String, val queued: Int,
        val checked: Int, val removed: Int, val refreshedOrFilled: Int, val status: String, val running: Boolean
    )

    /** `pendingReports` = the Worker's live report count (from /health). Fill + liveness
     *  backlogs are computed LOCALLY from the cached catalog (one cheap pass). */
    fun roleViews(pendingReports: Int): List<RoleView> {
        val snap = AvatarGlobalDb.snapshot()
        val cutoff = System.currentTimeMillis() - (if (blitzActive()) BLITZ_RECHECK_MS else RECHECK_INTERVAL_MS)
        var fill = 0; var la = 0; var lb = 0
        for (e in snap) {
            if (needsFill(e)) fill++
            if (e.checked < cutoff) { if (partitionOf(e.fileId) == 0) la++ else lb++ }
        }
        return Role.values().map { r ->
            val p = progress.getValue(r)
            val queued = when (r) {
                Role.REPORTS -> pendingReports
                Role.FILL -> fill
                Role.LIVENESS_A -> la
                Role.LIVENESS_B -> lb
            }
            RoleView(
                role = r,
                bot = if (p.slot >= 0) "bot ${p.slot + 1}" else "—",
                queued = queued,
                checked = p.checked,
                removed = p.removed,
                refreshedOrFilled = if (r == Role.FILL) p.filled else p.refreshed,
                status = p.status,
                running = p.running
            )
        }
    }

    // ---- the per-slot loop ---------------------------------------------------

    private suspend fun slotLoop(context: Context, adminKey: String, slot: Int, roles: List<Role>) {
        while (running && scope.isActive) {
            var did = false
            for (role in roles) {
                if (!running) break
                did = runRole(context, adminKey, slot, role) || did
            }
            if (!running) break
            delay(if (did) ACTIVE_PAUSE_MS else IDLE_SLEEP_MS)
        }
    }

    private suspend fun runRole(context: Context, adminKey: String, slot: Int, role: Role): Boolean = when (role) {
        Role.REPORTS -> reportsPass(context, adminKey, slot, role)
        Role.FILL -> fillPass(context, adminKey, slot, role)
        Role.LIVENESS_A -> livenessPass(context, adminKey, slot, role, partition = 0)
        Role.LIVENESS_B -> livenessPass(context, adminKey, slot, role, partition = 1)
    }

    // ---- shared helpers ------------------------------------------------------

    private suspend fun pushOps(
        context: Context, adminKey: String,
        upserts: List<AvatarGlobalDb.Entry>, removes: List<String>,
        clears: List<String> = emptyList(), checked: List<String> = emptyList()
    ): Boolean {
        val ok = AvatarGlobalDb.adminPush(context, adminKey, upserts, removes, clears, checked)
        pushError = if (ok) "" else "PUSH REJECTED — set ADMIN_KEY as a Secret on Cloudflare matching the app key"
        return ok
    }

    /** LIVENESS/REPORTS refresh: on a CONFIRMED-alive check (HTTP 200), pick up the
     *  owner's edits — a RENAME (name/author/platforms/image re-key) AND a description
     *  edit, INCLUDING a description the owner CLEARED (the fresh value is authoritative
     *  since it came from a 200, so an empty description means it's genuinely empty now).
     *  Name/author/platforms stay fill-preferring (a blank there on a 200 is unexpected,
     *  so we keep the good value rather than risk blanking it). Preserves `filled`.
     *  Returns the updated entry if anything changed, else null. */
    private fun liveRefresh(e: AvatarGlobalDb.Entry, chk: BotVrchatSession.AvatarCheck): AvatarGlobalDb.Entry? {
        val upd = e.copy(
            fileId = chk.fileId ?: e.fileId,
            name = chk.name.ifBlank { e.name },
            author = chk.author.ifBlank { e.author },
            authorId = chk.authorId.ifBlank { e.authorId },
            platforms = if (chk.platforms.isNotEmpty()) chk.platforms else e.platforms,
            description = chk.description   // authoritative — reflects an edited OR cleared bio
        )
        return if (upd != e) upd else null   // upd preserves checked+filled, so this compares the refreshable fields
    }

    /** FILL: fill-only refresh that ALWAYS marks the entry filled=true (so it's not
     *  re-scanned) — used by the first-fill bot. */
    private fun fillRefresh(e: AvatarGlobalDb.Entry, chk: BotVrchatSession.AvatarCheck): AvatarGlobalDb.Entry = e.copy(
        fileId = chk.fileId ?: e.fileId,
        name = chk.name.ifBlank { e.name },
        author = chk.author.ifBlank { e.author },
        authorId = chk.authorId.ifBlank { e.authorId },
        platforms = if (chk.platforms.isNotEmpty()) chk.platforms else e.platforms,
        description = chk.description.ifBlank { e.description },
        filled = true
    )

    private fun needsFill(e: AvatarGlobalDb.Entry): Boolean =
        !e.filled || e.platforms.isEmpty() || e.author.isBlank() || e.name.isBlank()

    private fun partitionOf(fileId: String): Int = (fileId.hashCode() and 0x7fffffff) % 2

    // ---- roles ---------------------------------------------------------------

    private suspend fun reportsPass(context: Context, adminKey: String, slot: Int, role: Role): Boolean {
        val p = progress.getValue(role)
        if (AvatarGlobalDb.pendingReportCount() <= 0) { p.status = "no reports — idle"; return false }
        val reports = AvatarGlobalDb.fetchReports(adminKey)
        if (reports.isEmpty()) { p.status = "no reports — idle"; return false }
        p.status = "verifying ${reports.size} report(s)"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val clears = mutableListOf<String>()
        for (r in reports) {
            if (!running) break
            if (r.avatarId.isBlank()) { clears.add(r.fileId); continue }
            val chk = BotVrchatSession.checkAvatar(context, slot, r.avatarId)
            p.checked++
            if (chk == null) { delay(PACE_MS); continue }
            if (!chk.alive) { removes.add(r.fileId); p.removed++ }
            else {
                clears.add(r.fileId)  // alive → false positive
                AvatarGlobalDb.lookup(r.fileId)?.let { cur ->
                    liveRefresh(cur, chk)?.let { upd ->
                        if (upd.fileId != cur.fileId) removes.add(cur.fileId)
                        upserts.add(upd); p.refreshed++
                    }
                }
            }
            if (upserts.size + removes.size + clears.size >= BATCH) {
                pushOps(context, adminKey, upserts.toList(), removes.toList(), clears.toList())
                upserts.clear(); removes.clear(); clears.clear()
            }
            delay(PACE_MS)
        }
        if (upserts.isNotEmpty() || removes.isNotEmpty() || clears.isNotEmpty())
            pushOps(context, adminKey, upserts.toList(), removes.toList(), clears.toList())
        p.status = "reports done"
        return true
    }

    private suspend fun fillPass(context: Context, adminKey: String, slot: Int, role: Role): Boolean {
        val p = progress.getValue(role)
        val entries = AvatarGlobalDb.snapshot().filter { needsFill(it) }.take(FILL_BATCH)
        if (entries.isEmpty()) { p.status = "all filled — idle"; return false }
        p.status = "filling ${entries.size} new/incomplete"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        for (e in entries) {
            if (!running) break
            val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
            p.checked++
            if (chk == null) { delay(PACE_MS); continue }
            if (!chk.alive) { removes.add(e.fileId); p.removed++ }
            else {
                val upd = fillRefresh(e, chk)
                if (upd.fileId != e.fileId) removes.add(e.fileId)  // re-key on image change
                upserts.add(upd); p.filled++
            }
            if (upserts.size + removes.size >= BATCH) {
                pushOps(context, adminKey, upserts.toList(), removes.toList())
                upserts.clear(); removes.clear()
            }
            delay(PACE_MS)
        }
        if (upserts.isNotEmpty() || removes.isNotEmpty())
            pushOps(context, adminKey, upserts.toList(), removes.toList())
        p.status = "fill: filled=${p.filled} removed=${p.removed}"
        return true
    }

    private suspend fun livenessPass(context: Context, adminKey: String, slot: Int, role: Role, partition: Int): Boolean {
        val p = progress.getValue(role)
        val cutoff = System.currentTimeMillis() - (if (blitzActive()) BLITZ_RECHECK_MS else RECHECK_INTERVAL_MS)
        val entries = AvatarGlobalDb.snapshot()
            .filter { partitionOf(it.fileId) == partition && it.checked < cutoff }
            .sortedBy { it.checked }.take(LIVENESS_BATCH)
        if (entries.isEmpty()) { p.status = "all fresh — idle"; return false }
        p.status = "checking oldest ${entries.size}${if (blitzActive()) " (blitz)" else ""}"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val okChecked = mutableListOf<String>()
        for (e in entries) {
            if (!running) break
            val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
            p.checked++
            if (chk == null) { delay(PACE_MS); continue }
            if (!chk.alive) { removes.add(e.fileId); p.removed++ }
            else {
                val upd = liveRefresh(e, chk)
                if (upd != null) {
                    if (upd.fileId != e.fileId) removes.add(e.fileId)
                    upserts.add(upd); p.refreshed++
                } else okChecked.add(e.fileId)  // alive + identical → just bump last-checked
            }
            delay(PACE_MS)
        }
        AvatarGlobalDb.markCheckedLocally(okChecked + upserts.map { it.fileId })
        if (removes.isNotEmpty() || upserts.isNotEmpty() || okChecked.isNotEmpty())
            pushOps(context, adminKey, upserts.toList(), removes.toList(), emptyList(), okChecked.toList())
        p.status = "checked=${p.checked} removed=${p.removed} refreshed=${p.refreshed}"
        return true
    }
}
