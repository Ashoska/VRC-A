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
    @Volatile private var runningSig = ""
    @Volatile private var blitzUntilMs = 0L
    fun blitzActive(): Boolean = System.currentTimeMillis() < blitzUntilMs

    /** Compute the role→slot assignment: honor every MANUAL choice (role→slot, if that
     *  slot is logged in), then LOAD-BALANCE the remaining roles onto the least-busy
     *  logged-in bots — so a manual pick is respected exactly and the rest spread out
     *  to DISTINCT bots instead of piling onto the one you picked (the "assignment is
     *  random / doesn't do that task" bug). */
    private fun computeRoleSlot(live: List<Int>, manual: Map<Role, Int>): Map<Role, Int> {
        if (live.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Role, Int>()
        val load = HashMap<Int, Int>().apply { live.forEach { put(it, 0) } }
        // 1. Manual picks first.
        for (r in Role.values()) {
            val s = manual[r]?.takeIf { it in live } ?: continue
            result[r] = s; load[s] = (load[s] ?: 0) + 1
        }
        // 2. Auto: each remaining role to the currently least-loaded bot (distinct where possible).
        for (r in Role.values()) {
            if (result.containsKey(r)) continue
            val s = live.minByOrNull { load[it] ?: 0 } ?: live[0]
            result[r] = s; load[s] = (load[s] ?: 0) + 1
        }
        return result
    }

    private fun sigOf(live: List<Int>, adminKey: String, roleSlot: Map<Role, Int>): String =
        live.joinToString(",") + "|" + adminKey.hashCode() + "|" +
            Role.values().joinToString(",") { "${it.ordinal}:${roleSlot[it]}" }

    /** Idempotent: (re)start ONLY when the logged-in bots / key / assignment actually
     *  changed. A no-op otherwise, so navigating away and back to the Bots tab keeps
     *  the running sweep + its counters instead of resetting them. */
    @Synchronized
    fun ensureRunning(context: Context, adminKey: String, manual: Map<Role, Int> = emptyMap()) {
        val app = context.applicationContext
        val key = adminKey.trim()
        val live = (0 until BotVrchatSession.SLOTS).filter { BotVrchatSession.isLoggedIn(app, it) }
        if (live.isEmpty() || key.isBlank()) { stop(); return }
        val sig = sigOf(live, key, computeRoleSlot(live, manual))
        // Restart when the assignment changed OR when a worker coroutine DIED unexpectedly.
        // An uncaught throw ends that one slot's job while `running` stays true, so without
        // this the Fill/Liveness sweep sits frozen forever — the bot shows "Authed" but its
        // queue never moves ("stuck, not checking"). Checking jobs.all{isActive} every tick
        // makes a dead slot self-heal within ~2s.
        if (running && sig == runningSig && jobs.isNotEmpty() && jobs.all { it.isActive }) return
        stop(); start(app, key, manual)
    }

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
    // Short idle poll (no network / no writes when idle — just a local snapshot scan) so
    // a bot notices new work FAST: a blitz, freshly-loaded catalog, or new reports kick
    // it within IDLE_SLEEP_MS instead of sleeping minutes. (This was 5 min — the cause of
    // "blitz does nothing" / "liveness stuck idle".)
    private const val IDLE_SLEEP_MS = 20_000L
    private const val ACTIVE_PAUSE_MS = 1_500L
    private const val CATALOG_POLL_MS = 30_000L          // poll the Worker's flush marker

    private const val BLITZ_BATCH = 40           // entries per blitz pass (per bot)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    /** A short human summary of which bot slot runs which roles (for the UI). */
    @Volatile var assignmentLabel = ""; private set

    // The logged-in slots (in order) + a per-slot blitz progress, so during a blitz EVERY
    // bot chews its OWN partition of the whole catalog (fill + dead-check) instead of the
    // reports/idle bots sitting out.
    @Volatile private var liveSlots: List<Int> = emptyList()
    private val blitzProgress = java.util.concurrent.ConcurrentHashMap<Int, Progress>()

    data class BlitzView(
        val slot: Int, val queued: Int, val checked: Int,
        val removed: Int, val filled: Int, val refreshed: Int, val status: String
    )

    /** Per-bot blitz progress + remaining backlog (empty when not blitzing). */
    fun blitzViews(): Map<Int, BlitzView> {
        if (!blitzActive()) return emptyMap()
        val live = liveSlots; val count = live.size
        if (count == 0) return emptyMap()
        val snap = AvatarGlobalDb.snapshot()
        val cutoff = System.currentTimeMillis() - BLITZ_RECHECK_MS
        return live.withIndex().associate { (idx, slot) ->
            val queued = snap.count {
                (it.fileId.hashCode() and 0x7fffffff) % count == idx && (needsFill(it) || it.checked < cutoff)
            }
            val p = blitzProgress[slot]
            slot to BlitzView(slot, queued, p?.checked ?: 0, p?.removed ?: 0, p?.filled ?: 0, p?.refreshed ?: 0,
                p?.status ?: "blitz")
        }
    }

    @Synchronized
    fun start(context: Context, adminKey: String, manual: Map<Role, Int> = emptyMap()) {
        if (running) return
        val app = context.applicationContext
        val live = (0 until BotVrchatSession.SLOTS).filter { BotVrchatSession.isLoggedIn(app, it) }
        if (live.isEmpty()) { progress.values.forEach { it.status = "no bot logged in" }; return }
        if (adminKey.isBlank()) { progress.values.forEach { it.status = "admin key not set" }; return }
        val roleSlot = computeRoleSlot(live, manual)
        runningSig = sigOf(live, adminKey.trim(), roleSlot)
        Role.values().forEach { r ->
            progress.getValue(r).apply { running = true; slot = roleSlot.getValue(r); checked = 0; refreshed = 0; removed = 0; filled = 0; status = "starting…" }
        }
        assignmentLabel = live.joinToString("  ·  ") { s ->
            val roles = roleSlot.filterValues { it == s }.keys.joinToString(", ") { it.label }
            "bot ${s + 1}: $roles"
        }
        running = true; pushError = ""
        liveSlots = live
        blitzProgress.clear(); live.forEach { blitzProgress[it] = Progress().apply { slot = it } }
        // One coroutine PER SLOT; it round-robins the roles assigned to that slot so a
        // single VRChat session never fires two roles in parallel (rate-limit safety).
        // liveIndex/liveCount give the bot its share of the catalog during a blitz.
        val bySlot = roleSlot.entries.groupBy({ it.value }, { it.key })
        for ((slot, roles) in bySlot) {
            val liveIndex = live.indexOf(slot)
            jobs += scope.launch {
                try { slotLoop(app, adminKey, slot, roles, liveIndex, live.size) }
                finally { roles.forEach { progress.getValue(it).running = false; progress.getValue(it).status = "stopped" } }
            }
        }
        // Pull the catalog the MOMENT the Worker rewrites the file: poll its cheap
        // /health lastFlush marker every 30s and, when it changes, force a cache-busted
        // refresh so newly-contributed (unfilled) avatars reach the FILL bot right away.
        // The local-progress merge (parseInto) means a re-pull never re-triggers already
        // done avatars. A stamp of "" on first run forces the initial pull.
        jobs += scope.launch {
            var seenFlush = ""
            while (running && scope.isActive) {
                try {
                    val flush = AvatarGlobalDb.workerLastFlush()
                    if (flush != null && flush != seenFlush) {
                        seenFlush = flush
                        AvatarGlobalDb.forceRefresh(app, cacheBust = flush)
                    }
                } catch (c: kotlinx.coroutines.CancellationException) { throw c }
                catch (t: Throwable) { android.util.Log.w("AvatarCatalogSweep", "catalog poll error", t) }
                delay(CATALOG_POLL_MS)
            }
        }
    }

    @Synchronized
    fun stop() {
        running = false
        runningSig = ""
        liveSlots = emptyList()
        jobs.forEach { it.cancel() }; jobs.clear()
        inFlight.clear()
        progress.values.forEach { it.running = false; it.status = "stopped" }
        blitzProgress.values.forEach { it.running = false }
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

    private suspend fun slotLoop(context: Context, adminKey: String, slot: Int, roles: List<Role>, liveIndex: Int, liveCount: Int) {
        while (running && scope.isActive) {
            // Per-cycle guard: ANY unexpected throw is logged + retried instead of ending
            // this slot's coroutine (which would freeze its queue permanently while `running`
            // stayed true). A cancellation still propagates (that's a real stop). This is the
            // primary fix for "queue stuck, bot not checking"; the ensureRunning watchdog is
            // the backstop.
            try {
                var did = false
                val blitzing = blitzActive()
                for (role in roles) {
                    if (!running) break
                    // During a blitz, EVERY bot shares the fill+dead-check work (below), so
                    // skip the assigned fill/liveness role here — but still run reports.
                    if (blitzing && role != Role.REPORTS) continue
                    did = runRole(context, adminKey, slot, role) || did
                }
                if (blitzing && running) did = blitzPass(context, adminKey, slot, liveIndex, liveCount) || did
                // LOAN: if this bot's own role(s) had no work and we're not blitzing, help the
                // biggest backlog (fill first, then stale liveness). The shared claim set means
                // no two bots ever touch the same avatar, and each bot is a SEPARATE VRChat
                // account, so loaning multiplies throughput without tripping one account's limit.
                if (!blitzing && running && !did) did = helpPass(context, adminKey, slot) || did
                if (!running) break
                // Responsive sleep: wake IMMEDIATELY (within 500ms) if the blitz state flips,
                // so clicking blitz kicks EVERY bot at once instead of each waiting out its
                // idle sleep (the "blitz start is inconsistent" cause).
                val target = if (did) ACTIVE_PAUSE_MS else IDLE_SLEEP_MS
                var slept = 0L
                while (slept < target && running && scope.isActive && blitzActive() == blitzing) {
                    delay(500); slept += 500
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                android.util.Log.w("AvatarCatalogSweep", "slot $slot cycle error — recovering", t)
                progress.values.filter { it.slot == slot }.forEach { it.status = "recovered from error" }
                delay(3000)
            }
        }
    }

    /** BLITZ: this bot processes ITS partition of the WHOLE catalog — fill the
     *  unfilled, dead-check the rest — so all logged-in bots work together to catch up
     *  everything. Partitioned by (fileId hash) % liveCount == liveIndex, so no two
     *  bots touch the same avatar. */
    private suspend fun blitzPass(context: Context, adminKey: String, slot: Int, liveIndex: Int, liveCount: Int): Boolean {
        if (liveCount <= 0) return false
        val p = blitzProgress.getOrPut(slot) { Progress().apply { this.slot = slot } }
        p.running = true
        val cutoff = System.currentTimeMillis() - BLITZ_RECHECK_MS
        val mine = AvatarGlobalDb.snapshot().filter { (it.fileId.hashCode() and 0x7fffffff) % liveCount == liveIndex }
        // Unfilled first, then stale — so bios/info fill fastest during a blitz.
        val batch = (mine.filter { needsFill(it) } + mine.filter { !needsFill(it) && it.checked < cutoff }).take(BLITZ_BATCH)
        if (batch.isEmpty()) { p.status = "blitz: caught up"; return false }
        p.status = "blitz: ${batch.size}"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val okChecked = mutableListOf<String>()
        for (e in batch) {
            if (!running) break
            val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
            p.checked++
            if (chk == null) { delay(PACE_MS); continue }
            if (!chk.alive) { removes.add(e.fileId); p.removed++ }
            else if (needsFill(e)) {
                val upd = fillRefresh(e, chk)
                if (upd.fileId != e.fileId) removes.add(e.fileId)
                upserts.add(upd); p.filled++
            } else {
                val upd = liveRefresh(e, chk)
                if (upd != null) {
                    if (upd.fileId != e.fileId) removes.add(e.fileId)
                    upserts.add(upd); p.refreshed++
                } else okChecked.add(e.fileId)
            }
            delay(PACE_MS)
        }
        AvatarGlobalDb.markCheckedLocally(okChecked + upserts.map { it.fileId })
        if (removes.isNotEmpty() || upserts.isNotEmpty() || okChecked.isNotEmpty())
            pushOps(context, adminKey, upserts.toList(), removes.toList(), emptyList(), okChecked.toList())
        return true
    }

    private suspend fun runRole(context: Context, adminKey: String, slot: Int, role: Role): Boolean = when (role) {
        Role.REPORTS -> reportsPass(context, adminKey, slot, role)
        Role.FILL -> fillPass(context, adminKey, slot, role)
        Role.LIVENESS_A -> livenessPass(context, adminKey, slot, role, partition = 0)
        Role.LIVENESS_B -> livenessPass(context, adminKey, slot, role, partition = 1)
    }

    // ---- shared helpers ------------------------------------------------------

    // Entries a bot is CURRENTLY processing. Claimed atomically so multiple bots — the
    // dedicated Fill/Liveness roles AND any idle bots loaning in — never grab the same
    // avatar. Released when the pass finishes (in a finally).
    private val inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun claimBatch(candidates: List<AvatarGlobalDb.Entry>, max: Int): List<AvatarGlobalDb.Entry> {
        val out = ArrayList<AvatarGlobalDb.Entry>(max)
        for (e in candidates) { if (out.size >= max) break; if (inFlight.add(e.fileId)) out.add(e) }
        return out
    }
    private fun release(fileIds: Collection<String>) { inFlight.removeAll(fileIds.toSet()) }

    private suspend fun pushOps(
        context: Context, adminKey: String,
        upserts: List<AvatarGlobalDb.Entry>, removes: List<String>,
        clears: List<String> = emptyList(), checked: List<String> = emptyList()
    ): Boolean {
        val ok = AvatarGlobalDb.adminPush(context, adminKey, upserts, removes, clears, checked)
        // Apply to the LOCAL catalog immediately so the backlog counts drop live (the
        // authoritative copy is still the Worker's; this just avoids the ~15-min flush lag).
        if (ok) AvatarGlobalDb.applyAdminLocal(upserts, removes)
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
            description = chk.description,  // authoritative — reflects an edited OR cleared bio
            // Perf can change on a re-upload; take the known value, keep the old if unknown.
            perfPc = if (chk.perfPc < 5) chk.perfPc else e.perfPc,
            perfQuest = if (chk.perfQuest < 5) chk.perfQuest else e.perfQuest,
            perfIos = if (chk.perfIos < 5) chk.perfIos else e.perfIos
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
        perfPc = if (chk.perfPc < 5) chk.perfPc else e.perfPc,
        perfQuest = if (chk.perfQuest < 5) chk.perfQuest else e.perfQuest,
        perfIos = if (chk.perfIos < 5) chk.perfIos else e.perfIos,
        filled = true
    )

    // "Needs fill" = the bot hasn't SUCCESSFULLY fetched this entry yet. Keyed ONLY on
    // `filled` — NOT on empty platforms/author/name — because a public avatar can legit
    // return empty for those, and the old OR-conditions re-queued such an entry FOREVER
    // (filled=true but still "needs fill"), so the Fill queue plateaued and never drained
    // while the bot re-checked the same un-satisfiable entries. Once filled=true it's done;
    // any later owner edit (new platform/name/bio) is picked up by the liveness refresh.
    private fun needsFill(e: AvatarGlobalDb.Entry): Boolean = !e.filled

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
        val batch = claimBatch(AvatarGlobalDb.snapshot().filter { needsFill(it) }, FILL_BATCH)
        if (batch.isEmpty()) { progress.getValue(role).status = "all filled — idle"; return false }
        return processFillBatch(context, adminKey, slot, batch, role)
    }

    private suspend fun livenessPass(context: Context, adminKey: String, slot: Int, role: Role, partition: Int): Boolean {
        val cutoff = System.currentTimeMillis() - (if (blitzActive()) BLITZ_RECHECK_MS else RECHECK_INTERVAL_MS)
        val cand = AvatarGlobalDb.snapshot()
            .filter { partitionOf(it.fileId) == partition && it.checked < cutoff }
            .sortedBy { it.checked }
        val batch = claimBatch(cand, LIVENESS_BATCH)
        if (batch.isEmpty()) { progress.getValue(role).status = "all fresh — idle"; return false }
        return processLivenessBatch(context, adminKey, slot, batch, role)
    }

    /** LOAN pass: this bot's own role has no work, so help the largest backlog — FILL
     *  first (the usual bottleneck), then the stale-liveness sweep — drawing from the SAME
     *  claim set as the dedicated bots so nothing is double-processed. Counters land on the
     *  HELPED role so its throughput reflects every bot pitching in. */
    private suspend fun helpPass(context: Context, adminKey: String, slot: Int): Boolean {
        val fillBatch = claimBatch(AvatarGlobalDb.snapshot().filter { needsFill(it) }, FILL_BATCH)
        if (fillBatch.isNotEmpty()) return processFillBatch(context, adminKey, slot, fillBatch, Role.FILL)
        val cutoff = System.currentTimeMillis() - (if (blitzActive()) BLITZ_RECHECK_MS else RECHECK_INTERVAL_MS)
        val staleBatch = claimBatch(
            AvatarGlobalDb.snapshot().filter { it.checked < cutoff }.sortedBy { it.checked }, LIVENESS_BATCH
        )
        if (staleBatch.isEmpty()) return false
        return processLivenessBatch(context, adminKey, slot, staleBatch, Role.LIVENESS_A)
    }

    /** Process a PRE-CLAIMED fill batch (used by the FILL role AND by loaning bots). Backs
     *  off the moment VRChat rate-limits (3 nulls in a row) so it doesn't hammer a throttled
     *  account — the next cycle retries the released entries. Releases the whole claim in a
     *  finally, so a break/exception can never permanently strand entries. */
    private suspend fun processFillBatch(
        context: Context, adminKey: String, slot: Int, batch: List<AvatarGlobalDb.Entry>, role: Role
    ): Boolean {
        val p = progress.getValue(role)
        p.status = "filling ${batch.size}"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        try {
            var nulls = 0
            for (e in batch) {
                if (!running) break
                val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
                p.checked++
                if (chk == null) {
                    if (++nulls >= 3) { p.status = "rate-limited — backing off"; break }
                    delay(PACE_MS); continue
                }
                nulls = 0
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
        } finally { release(batch.map { it.fileId }) }
    }

    /** Process a PRE-CLAIMED liveness batch (dead-check + refresh), same rate-limit backoff. */
    private suspend fun processLivenessBatch(
        context: Context, adminKey: String, slot: Int, batch: List<AvatarGlobalDb.Entry>, role: Role
    ): Boolean {
        val p = progress.getValue(role)
        p.status = "checking oldest ${batch.size}${if (blitzActive()) " (blitz)" else ""}"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val okChecked = mutableListOf<String>()
        try {
            var nulls = 0
            for (e in batch) {
                if (!running) break
                val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
                p.checked++
                if (chk == null) {
                    if (++nulls >= 3) { p.status = "rate-limited — backing off"; break }
                    delay(PACE_MS); continue
                }
                nulls = 0
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
        } finally { release(batch.map { it.fileId }) }
    }
}
