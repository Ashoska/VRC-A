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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

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
        /** Non-blank ("Fill"/"Liveness") while this bot's own role has no work and it is
         *  LOANING itself to another backlog — so the UI can show it's helping, not idle. */
        @Volatile var helping = ""
    }

    private val progress = Role.values().associateWith { Progress() }
    fun progressOf(role: Role): Progress = progress.getValue(role)

    @Volatile var running = false; private set
    @Volatile var pushError = ""; private set
    /** The TRUE total backlog (fill + liveness + reports), set on each roleViews() pass.
     *  The per-bot queued numbers are SPLIT shares of a backlog; this is the real total for
     *  the "To process" pill (so splitting the display never distorts the grand total). */
    @Volatile var lastTotalBacklog = 0; private set
    @Volatile private var runningSig = ""
    @Volatile private var blitzUntilMs = 0L
    // The instant the CURRENT blitz began. During a blitz the liveness cutoff is anchored
    // HERE (not a rolling "now - 1h"), so an avatar checked during the blitz — checked > this
    // — is never re-selected, and the blitz queue drains to ZERO instead of treadmilling.
    // (The old rolling 1h window made a full-catalog sweep, which takes HOURS at 1.2s/avatar,
    // re-queue avatars it had already checked this same blitz — the "did 17k, still 7.7k left"
    // bug.) Anchored once per fresh blitz; a re-press only EXTENDS the window, keeping the anchor.
    @Volatile private var blitzStartMs = 0L
    fun blitzActive(): Boolean = System.currentTimeMillis() < blitzUntilMs

    /** The staleness cutoff for the liveness/blitz sweeps: during a blitz it's the blitz
     *  START (each avatar checked at most once per blitz → the queue converges to 0);
     *  otherwise the normal 7-day recheck interval. */
    private fun livenessCutoff(): Long =
        if (blitzActive()) blitzStartMs else System.currentTimeMillis() - RECHECK_INTERVAL_MS

    // ---- process-lifetime proof-of-life -------------------------------------
    // Unlike the per-role/blitz Progress counters (zeroed on every ensureRunning restart),
    // this timestamp updates on EVERY slot-loop cycle — including idle cycles when there's
    // no work — so the admin can tell "bots alive, just caught up" apart from "bots dead"
    // even when the backlog numbers are flat. Surfaced in the Bots tab via BotController.
    @Volatile var lastCycleMs = 0L; private set
    /** True while the sweep loop has cycled within the last minute (alive, even if idle). */
    fun sweepAlive(): Boolean = running && System.currentTimeMillis() - lastCycleMs < 60_000L

    // VRChat-OUTAGE guard: only REMOVE an avatar (dead-check) when VRChat has recently
    // confirmed SOMETHING alive (a real 200). If every check is failing (VRChat down / a
    // network outage / rate-limited to death), a stray 404/403 could be FALSE, so a removal
    // is DEFERRED until a live 200 proves VRChat is reachable again. This is what stops a
    // VRChat outage from mass-deleting real avatars. Re-keys + refreshes (which only happen on
    // a 200) are unaffected. `noteAlive()` is called on every alive check.
    @Volatile private var lastAliveMs = 0L
    private const val OUTAGE_GUARD_MS = 90_000L
    private fun noteAlive() { lastAliveMs = System.currentTimeMillis() }
    private fun canRemove(): Boolean = System.currentTimeMillis() - lastAliveMs < OUTAGE_GUARD_MS

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
    private const val RECHECK_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000  // 7 days (steady-state recheck)
    private const val BLITZ_WINDOW_MS = 30L * 60 * 1000                // initial blitz window per press
    // While a blitz is actively doing work, keep its window rolling forward by this much, so a
    // full-catalog blitz that takes >30 min doesn't EXPIRE mid-way (the "blitz queue disappears
    // before it's complete" bug). Once every bot is caught up (no more work), no bot extends it,
    // so it self-ends ~this long after the last work — it can't run forever (the blitz-start
    // cutoff means an avatar checked during the blitz is never re-selected, so it converges).
    private const val BLITZ_KEEPALIVE_MS = 5L * 60 * 1000
    // Short idle poll (no network / no writes when idle — just a local snapshot scan) so
    // a bot notices new work FAST: a blitz, freshly-loaded catalog, or new reports kick
    // it within IDLE_SLEEP_MS instead of sleeping minutes. (This was 5 min — the cause of
    // "blitz does nothing" / "liveness stuck idle".)
    private const val IDLE_SLEEP_MS = 20_000L
    private const val ACTIVE_PAUSE_MS = 1_500L

    private const val BLITZ_BATCH = 40           // entries per blitz pass (per bot)
    // An idle bot only LOANS to another role's backlog once that backlog is genuinely
    // "in the red" — matches BotsTab's queued>500 red threshold. Below this, the idle
    // bot stays idle and lets the dedicated bot handle its own small backlog, instead
    // of every free bot piling onto a minor queue. (Keep in sync with BotsTab's red.)
    private const val LOAN_RED_THRESHOLD = 500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableListOf<Job>()

    // ---- time-based Worker flush (bounds Cloudflare KV writes) ----------------
    // Every op is applied to the LOCAL catalog IMMEDIATELY (free, in-memory — this is
    // what makes the backlog counters tick down smoothly per avatar instead of
    // teleporting a whole batch at a time), and the Worker push is BUFFERED here. One
    // timer drains the buffer to /admin every FLUSH_MS in size-capped chunks, so KV
    // write volume is bounded by TIME, not by how many avatars are processed — a blitz
    // can churn thousands/min and still cost only a few writes/min. Failed chunks are
    // re-queued (eventual consistency); a buffer lost to process death self-heals on
    // restart (the entry re-pulls unfilled/stale and is re-processed).
    private const val FLUSH_MS = 60_000L
    private const val FLUSH_CHUNK = 200            // ops per /admin push (≈ one KV write each)
    private val pendingUpserts = java.util.concurrent.ConcurrentHashMap<String, AvatarGlobalDb.Entry>()
    private val pendingRemoves = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingClears = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val flushMutex = Mutex()
    private val flusherStarted = AtomicBoolean(false)
    @Volatile private var flushCtx: Context? = null
    @Volatile private var flushKey = ""

    /** A short human summary of which bot slot runs which roles (for the UI). */
    @Volatile var assignmentLabel = ""; private set

    // Role->slot the bots WOULD run, computed from the currently logged-in bots + saved
    // manual picks even while the sweep is STOPPED/paused — so the Bots tab can attribute
    // each role's queued backlog to its bot BEFORE the admin presses Start (otherwise the
    // per-bot queued rows only appeared once running, since progress[r].slot was -1).
    @Volatile private var assignmentPreview: Map<Role, Int> = emptyMap()

    /** Recompute the preview assignment from the current live bots + manual picks.
     *  Called every UI tick by BotController, running or not. */
    fun setAssignmentPreview(context: Context, manual: Map<Role, Int> = emptyMap()) {
        val app = context.applicationContext
        val live = (0 until BotVrchatSession.SLOTS).filter { BotVrchatSession.isLoggedIn(app, it) }
        assignmentPreview = if (live.isEmpty()) emptyMap() else computeRoleSlot(live, manual)
    }

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
        val cutoff = livenessCutoff()
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
        // Wire the time-based flusher (context + current admin key) and launch it ONCE.
        // It lives on `scope` (NOT in `jobs`, which stop() cancels), so buffered ops still
        // reach the Worker after the sweep is stopped/paused.
        flushCtx = app; flushKey = adminKey.trim()
        if (flusherStarted.compareAndSet(false, true)) {
            scope.launch {
                while (true) {
                    try { flushPending() } catch (_: Throwable) { /* retry next tick */ }
                    delay(FLUSH_MS)
                }
            }
        }
        if (blitzActive()) {
            // Mid-blitz restart (watchdog / re-login): KEEP the running blitz counters instead
            // of wiping them (part of the "blitz queue randomly disappears" reset).
            live.forEach { blitzProgress.getOrPut(it) { Progress().apply { slot = it } } }
        } else {
            blitzProgress.clear(); live.forEach { blitzProgress[it] = Progress().apply { slot = it } }
        }
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
        // NOTE: the catalog freshness poll (workerLastFlush -> forceRefresh) lives in
        // BotController now (always-on, even when the sweep is STOPPED) so the admin can see
        // the real backlog counts to decide whether to run the bots. See BotController.start().
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
    fun requestFullBlitz() {
        val now = System.currentTimeMillis()
        // Fresh blitz → anchor the cutoff to NOW so every currently-stale avatar is swept
        // exactly once. A re-press while one is already running only EXTENDS the window and
        // KEEPS the anchor, so it keeps draining the remainder instead of restarting the sweep.
        if (!blitzActive()) blitzStartMs = now
        blitzUntilMs = now + BLITZ_WINDOW_MS
    }

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
        val checked: Int, val removed: Int, val refreshedOrFilled: Int, val status: String, val running: Boolean,
        /** Non-blank ("Fill"/"Liveness") when this bot is loaning to another backlog. */
        val helping: String = ""
    )

    /** `pendingReports` = the Worker's live report count (from /health). Fill + liveness
     *  backlogs are computed LOCALLY from the cached catalog (one cheap pass). */
    fun roleViews(pendingReports: Int): List<RoleView> {
        val snap = AvatarGlobalDb.snapshot()
        val cutoff = livenessCutoff()
        var fill = 0; var la = 0; var lb = 0
        for (e in snap) {
            if (needsFill(e)) fill++
            if (e.checked < cutoff) { if (partitionOf(e.fileId) == 0) la++ else lb++ }
        }
        val liveness = la + lb
        lastTotalBacklog = fill + liveness + pendingReports

        // Which backlog each role is consuming RIGHT NOW — its own role, or the one it's
        // loaning to. Then split that backlog EVENLY across all the bots on it, so the UI shows
        // "6181 split 4 ways = ~1546 each" and re-splits the instant a bot returns to its own
        // field (e.g. reports arrive → 3 bots left on fill → each share jumps).
        fun targetOf(r: Role): String = when (progress.getValue(r).helping) {
            "Fill" -> "Fill"
            "Liveness" -> "Liveness"
            else -> when (r) { Role.REPORTS -> "Reports"; Role.FILL -> "Fill"; else -> "Liveness" }
        }
        val workers = Role.values().groupingBy { targetOf(it) }.eachCount()
        fun backlogOf(t: String) = when (t) { "Fill" -> fill; "Liveness" -> liveness; "Reports" -> pendingReports; else -> 0 }

        return Role.values().map { r ->
            val p = progress.getValue(r)
            val t = targetOf(r)
            val n = (workers[t] ?: 1).coerceAtLeast(1)
            val share = (backlogOf(t) + n - 1) / n   // ceil-split among the bots on this backlog
            // While running, use the live progress slot; while stopped, fall back to the
            // preview assignment so the per-bot queued rows show BEFORE Start.
            val slot = p.slot.takeIf { it >= 0 } ?: assignmentPreview[r] ?: -1
            RoleView(
                role = r,
                bot = if (slot >= 0) "bot ${slot + 1}" else "—",
                queued = share,
                checked = p.checked,
                removed = p.removed,
                // While loaning to Fill, show the filled count (not this role's refreshed 0).
                refreshedOrFilled = if (r == Role.FILL || p.helping == "Fill") p.filled else p.refreshed,
                status = p.status,
                running = p.running,
                helping = p.helping
            )
        }
    }

    // ---- the per-slot loop ---------------------------------------------------

    private suspend fun slotLoop(context: Context, adminKey: String, slot: Int, roles: List<Role>, liveIndex: Int, liveCount: Int) {
        val ownRole = roles.firstOrNull()
        while (running && scope.isActive) {
            // Proof-of-life heartbeat: stamped every cycle (work or idle) so the UI can show
            // the sweep is alive even when the backlog is 0 / nothing is moving.
            lastCycleMs = System.currentTimeMillis()
            // Each cycle starts NOT helping; helpPass sets the marker if this bot loans.
            ownRole?.let { progress.getValue(it).helping = "" }
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
                if (!blitzing && running && !did && ownRole != null)
                    did = helpPass(context, adminKey, slot, ownRole) || did
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
        val cutoff = livenessCutoff()
        val mine = AvatarGlobalDb.snapshot().filter { (it.fileId.hashCode() and 0x7fffffff) % liveCount == liveIndex }
        // Unfilled first, then stale — so bios/info fill fastest during a blitz.
        val batch = (mine.filter { needsFill(it) } + mine.filter { !needsFill(it) && it.checked < cutoff }).take(BLITZ_BATCH)
        if (batch.isEmpty()) { p.status = "blitz: caught up"; return false }
        // Still work to do → roll the blitz window forward so it doesn't expire mid-catalog.
        blitzUntilMs = maxOf(blitzUntilMs, System.currentTimeMillis() + BLITZ_KEEPALIVE_MS)
        p.status = "blitz: ${batch.size}"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val okChecked = mutableListOf<String>()
        for (e in batch) {
            if (!running) break
            val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
            p.checked++
            if (chk == null) { delay(PACE_MS); continue }
            if (chk.alive) noteAlive()
            if (!chk.alive) {
                if (canRemove()) { removes.add(e.fileId); p.removed++; applyItemLocal(remove = e.fileId) }
                // else: suspected VRChat outage — defer the removal
            }
            else if (needsFill(e)) {
                val upd = fillRefresh(e, chk)
                if (upd.fileId != e.fileId) removes.add(e.fileId)
                upserts.add(upd); p.filled++
                applyItemLocal(upd = upd, rekeyFrom = if (upd.fileId != e.fileId) e.fileId else null)
            } else {
                val upd = liveRefresh(e, chk)
                if (upd != null) {
                    if (upd.fileId != e.fileId) removes.add(e.fileId)
                    upserts.add(upd); p.refreshed++
                    applyItemLocal(upd = upd, rekeyFrom = if (upd.fileId != e.fileId) e.fileId else null)
                } else { okChecked.add(e.fileId); applyItemLocal(checked = e.fileId) }
            }
            delay(PACE_MS)
        }
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

    // Buffer a pass's Worker ops for the time-based flush. The LOCAL catalog is applied
    // PER ITEM in the loops (smooth counters), so this does NO local apply and NO network
    // — it only queues the ops. context/adminKey are unused (the flusher holds them from
    // start()) but kept so the call sites don't change.
    private fun pushOps(
        context: Context, adminKey: String,
        upserts: List<AvatarGlobalDb.Entry>, removes: List<String>,
        clears: List<String> = emptyList(), checked: List<String> = emptyList()
    ) {
        enqueueOps(upserts, removes, clears, checked)
    }

    /** Add ops to the time-based flush buffer. Dedups by file id; a remove supersedes a
     *  pending upsert of the same id (and clears its pending checked-bump), and an upsert
     *  cancels a pending remove — so re-keys and rapid re-processing can't leave stale
     *  buffered ops. NO network here; the flusher drains it. */
    private fun enqueueOps(
        upserts: List<AvatarGlobalDb.Entry>, removes: List<String>,
        clears: List<String> = emptyList(), checked: List<String> = emptyList()
    ) {
        for (e in upserts) { pendingRemoves.remove(e.fileId); pendingUpserts[e.fileId] = e }
        for (fid in removes) { pendingUpserts.remove(fid); pendingChecked.remove(fid); pendingRemoves.add(fid) }
        for (fid in clears) pendingClears.add(fid)
        for (fid in checked) if (!pendingUpserts.containsKey(fid) && !pendingRemoves.contains(fid)) pendingChecked.add(fid)
    }

    /** Drain the buffered ops to the Worker in size-capped chunks (≈ one KV write each),
     *  re-queuing anything that fails so nothing is lost. Called on the FLUSH_MS timer,
     *  so KV writes scale with TIME, not with how fast the bots process avatars. */
    private suspend fun flushPending() {
        val ctx = flushCtx ?: return
        if (flushKey.isBlank()) return
        flushMutex.withLock {
            val upserts = ArrayDeque(pendingUpserts.values.toList()); pendingUpserts.clear()
            val removes = ArrayDeque(pendingRemoves.toList()); pendingRemoves.clear()
            val clears = ArrayDeque(pendingClears.toList()); pendingClears.clear()
            val checked = ArrayDeque(pendingChecked.toList()); pendingChecked.clear()
            if (upserts.isEmpty() && removes.isEmpty() && clears.isEmpty() && checked.isEmpty()) { pushError = ""; return }
            var failed = false
            while (upserts.isNotEmpty() || removes.isNotEmpty() || clears.isNotEmpty() || checked.isNotEmpty()) {
                val u = ArrayList<AvatarGlobalDb.Entry>(); repeat(FLUSH_CHUNK) { upserts.removeFirstOrNull()?.let(u::add) }
                val r = ArrayList<String>(); repeat(FLUSH_CHUNK) { removes.removeFirstOrNull()?.let(r::add) }
                val c = ArrayList<String>(); repeat(FLUSH_CHUNK) { clears.removeFirstOrNull()?.let(c::add) }
                val k = ArrayList<String>(); repeat(FLUSH_CHUNK) { checked.removeFirstOrNull()?.let(k::add) }
                if (u.isEmpty() && r.isEmpty() && c.isEmpty() && k.isEmpty()) break
                if (!AvatarGlobalDb.adminPush(ctx, flushKey, u, r, c, k)) {
                    // Re-queue this chunk + everything still pending, retry next flush.
                    (u + upserts).forEach { pendingUpserts.putIfAbsent(it.fileId, it) }
                    (r + removes).forEach { pendingRemoves.add(it) }
                    (c + clears).forEach { pendingClears.add(it) }
                    (k + checked).forEach { if (!pendingUpserts.containsKey(it) && !pendingRemoves.contains(it)) pendingChecked.add(it) }
                    failed = true; break
                }
            }
            pushError = if (failed) "PUSH REJECTED — set ADMIN_KEY as a Secret on Cloudflare matching the app key" else ""
        }
    }

    /** Reflect ONE processed avatar in the LOCAL catalog right away (free, in-memory) so
     *  the backlog counters tick down per avatar instead of jumping a whole batch at once.
     *  The Worker push is buffered separately (pushOps → enqueueOps). */
    private fun applyItemLocal(upd: AvatarGlobalDb.Entry? = null, rekeyFrom: String? = null,
                              remove: String? = null, checked: String? = null) {
        val ups = if (upd != null) listOf(upd) else emptyList()
        val rems = listOfNotNull(rekeyFrom, remove)
        if (ups.isNotEmpty() || rems.isNotEmpty()) AvatarGlobalDb.applyAdminLocal(ups, rems)
        if (checked != null) AvatarGlobalDb.markCheckedLocally(listOf(checked))
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
            if (chk.alive) noteAlive()
            if (!chk.alive) {
                // Don't remove on a report during a suspected VRChat outage — a 404 could be
                // false. Skip clearing too, so the report stays pending and is re-verified once
                // VRChat is back (don't mark a maybe-alive avatar's report resolved).
                if (canRemove()) { removes.add(r.fileId); p.removed++; applyItemLocal(remove = r.fileId) }
            }
            else {
                clears.add(r.fileId)  // alive → false positive
                AvatarGlobalDb.lookup(r.fileId)?.let { cur ->
                    liveRefresh(cur, chk)?.let { upd ->
                        if (upd.fileId != cur.fileId) removes.add(cur.fileId)
                        upserts.add(upd); p.refreshed++
                        applyItemLocal(upd = upd, rekeyFrom = if (upd.fileId != cur.fileId) cur.fileId else null)
                    } ?: applyItemLocal(checked = r.fileId)  // alive + identical → bump last-checked
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
        val cutoff = livenessCutoff()
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
    private suspend fun helpPass(context: Context, adminKey: String, slot: Int, ownRole: Role): Boolean {
        // Attribute the loaned work to the BOT'S OWN role progress (+ a `helping` marker) so
        // each bot's card shows what IT is doing, instead of all the loaned work vanishing into
        // the Fill card and the idle bots looking like they do nothing.
        //
        // GATE: only loan when the target backlog is genuinely "in the red"
        // (> LOAN_RED_THRESHOLD, matching the UI). Below that, the idle bot stays idle and
        // leaves the dedicated bot to work its own small backlog — no piling onto a minor
        // queue. Blitz is unaffected (it uses every bot regardless and never calls helpPass).
        val snap = AvatarGlobalDb.snapshot()
        val fillItems = snap.filter { needsFill(it) }
        if (fillItems.size > LOAN_RED_THRESHOLD) {
            val fillBatch = claimBatch(fillItems, FILL_BATCH)
            if (fillBatch.isNotEmpty()) {
                progress.getValue(ownRole).helping = "Fill"
                return processFillBatch(context, adminKey, slot, fillBatch, ownRole)
            }
        }
        val cutoff = livenessCutoff()
        val staleItems = snap.filter { it.checked < cutoff }
        if (staleItems.size > LOAN_RED_THRESHOLD) {
            val staleBatch = claimBatch(staleItems.sortedBy { it.checked }, LIVENESS_BATCH)
            if (staleBatch.isNotEmpty()) {
                progress.getValue(ownRole).helping = "Liveness"
                return processLivenessBatch(context, adminKey, slot, staleBatch, ownRole)
            }
        }
        return false
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
                if (chk.alive) noteAlive()
                if (!chk.alive) {
                    if (canRemove()) { removes.add(e.fileId); p.removed++; applyItemLocal(remove = e.fileId) }
                    // else: suspected VRChat outage — defer the removal, retry next pass
                } else {
                    val upd = fillRefresh(e, chk)
                    if (upd.fileId != e.fileId) removes.add(e.fileId)  // re-key on image change (safe on a 200)
                    upserts.add(upd); p.filled++
                    applyItemLocal(upd = upd, rekeyFrom = if (upd.fileId != e.fileId) e.fileId else null)
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
                if (chk.alive) noteAlive()
                if (!chk.alive) {
                    if (canRemove()) { removes.add(e.fileId); p.removed++; applyItemLocal(remove = e.fileId) }
                    // else: suspected VRChat outage — defer the removal, retry next pass
                } else {
                    val upd = liveRefresh(e, chk)
                    if (upd != null) {
                        if (upd.fileId != e.fileId) removes.add(e.fileId)
                        upserts.add(upd); p.refreshed++
                        applyItemLocal(upd = upd, rekeyFrom = if (upd.fileId != e.fileId) e.fileId else null)
                    } else { okChecked.add(e.fileId); applyItemLocal(checked = e.fileId) }  // alive + identical → bump last-checked
                }
                delay(PACE_MS)
            }
            if (removes.isNotEmpty() || upserts.isNotEmpty() || okChecked.isNotEmpty())
                pushOps(context, adminKey, upserts.toList(), removes.toList(), emptyList(), okChecked.toList())
            p.status = "checked=${p.checked} removed=${p.removed} refreshed=${p.refreshed}"
            return true
        } finally { release(batch.map { it.fileId }) }
    }
}
