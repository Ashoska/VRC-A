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
 *  - **LIVENESS A/B** — passive oldest-checked-first dead/refresh sweep.
 *
 * NOTE (post-cutover / shard-walk mode — the LIVE path when `AvatarGlobalDb.shardWalkLive()`):
 * the role labels are just IDENTITY. Every bot pulls the next shard prefix from a shared work
 * source (worklist → oldest-swept) and does fill + refresh + dead-check on the whole shard;
 * overlap is prevented by the shared queue/claim (`workQueue.poll()`, `shardClaims`, per-avatar
 * `inFlight`/`claimBatch`), NOT by the `fileId % 2` partition. The `%2` partition + `blitzPass`/
 * `blitzViews` only run in the PRE-CUTOVER local-map fallback. In walk mode the Bots tab folds a
 * bot's roles into ONE row (see roleViews) since they all chew the same pool.
 *
 * Roles are assigned to whichever bot slots are logged in (1..4). With 4 bots, one
 * role each; with fewer, a slot round-robins several roles.
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
        // Shards walked (shard-walk mode) — the bots grab a WHOLE shard and recheck everything in
        // it, so this is the meaningful unit of work now (one shard = one cheap R2 write), not the
        // per-avatar `checked`. Surfaced in the UI so the admin sees shard throughput.
        @Volatile var shards = 0
        // DEDICATED report counters so the Reports bot's real activity is visible even when it's
        // ALSO loaning to the shard walk (which would otherwise pollute checked/removed with walk
        // work). reportsVerified = reports it dead-checked; reportsRemoved = confirmed-dead culls.
        @Volatile var reportsVerified = 0
        @Volatile var reportsRemoved = 0
        @Volatile var status = "idle"
        /** Non-blank ("Fill"/"Liveness") while this bot's own role has no work and it is
         *  LOANING itself to another backlog — so the UI can show it's helping, not idle. */
        @Volatile var helping = ""
    }

    private val progress = Role.values().associateWith { Progress() }
    fun progressOf(role: Role): Progress = progress.getValue(role)

    @Volatile var running = false; private set
    // HARD pause gate, driven directly by BotController.applySweepConfig every ~2s from the
    // saved bots_paused pref (independent of stop()/job cancellation). Checked at the top of
    // every worker cycle + before every counter-touching batch, so a paused sweep does ZERO
    // work and can NEVER advance a counter even if a coroutine somehow survived or restarted.
    @Volatile var paused = false; private set
    fun setPaused(v: Boolean) { paused = v }
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
    // Steady-state recheck cadence. Widened 7d -> 30d: re-verifying a low-use catalog weekly was
    // ~4x the R2 write/read churn (every re-sweep bumps `checked` on a whole shard = a shard write,
    // plus a shard read) for little benefit — a dead avatar is caught the moment a user tries to
    // clone it (report path). 30d keeps proactive culling while cutting the liveness cost ~4x.
    private const val RECHECK_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000  // 30 days (steady-state recheck)
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
    // Hard cap on the buffered-ops count before an IMMEDIATE flush (don't wait out FLUSH_MS). Bounds
    // the buffer + push latency during a burst/blitz; the same 1k cap the user contributions use.
    private const val BOT_FLUSH_MAX_BATCH = 1000
    private val capFlushing = AtomicBoolean(false)   // one in-flight cap-triggered flush at a time
    private val pendingUpserts = java.util.concurrent.ConcurrentHashMap<String, AvatarGlobalDb.Entry>()
    private val pendingRemoves = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingClears = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val pendingChecked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Bot-detected author display-name changes (authorId -> new name), flushed to /admin so the Worker
    // renames ALL of that creator's avatars catalog-wide. Authoritative (bot GET /avatars/{id}).
    private val pendingAuthorRenames = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val flushMutex = Mutex()
    private val flusherStarted = AtomicBoolean(false)
    @Volatile private var flushCtx: Context? = null
    @Volatile private var flushKey = ""

    // ---- avtrdb digestion crawl (admin bots, OPT-IN, OFF by default) ----------
    // ALL logged-in bots crawl TOGETHER: it's a MODE inside the per-slot loop (see slotLoop),
    // so each bot does EITHER its role OR a crawl pass in a given cycle — never both, so a bot's
    // session is never double-loaded (the role/crawl collision fix). Bots pull terms from a
    // SHARED atomic cursor (no overlap, self-balancing) and share the `inFlight` claim set so
    // no avatar is resolved twice. Resolution runs on the BOT session (never a public user's).
    // OFF by default so the build ships BEFORE the sharding migration; flip on (Bots tab) after.
    @Volatile var avtrdbCrawlEnabled = false
    @Volatile var avtrdbCrawlStatus = "off"; private set
    // Enumeration terms for the avtrdb crawl. The old set was ONLY a-z + 0-9, so it never discovered
    // avatars whose name has no Latin letter (Japanese, Korean, Russian, Arabic, …). avtrdb search is
    // substring-based, so a single common character of a script matches most names in that script.
    // We include the FULL small syllabaries/alphabets (hiragana, katakana, Cyrillic, Arabic) for
    // complete coverage, plus a curated set of common CJK + Korean characters. isLetterOrDigit is
    // Unicode-aware and searchPage URL-encodes, so non-Latin terms work end-to-end.
    private val CRAWL_TERMS: List<String> = buildList {
        ('a'..'z').forEach { add(it.toString()) }
        ('0'..'9').forEach { add(it.toString()) }
        ('ぁ'..'ゖ').forEach { add(it.toString()) }   // Hiragana
        ('ァ'..'ヺ').forEach { add(it.toString()) }   // Katakana
        ('а'..'я').forEach { add(it.toString()) }   // Cyrillic (lowercase а..я)
        ('ا'..'ي').forEach { add(it.toString()) }   // Arabic letters
        // Common CJK / kanji that show up in avatar + character names
        "猫犬狐兎狼熊龍竜星月花鳥魚水火風光闇天神鬼獣羊姫少女少年悪魔天使妖精精霊魔法可愛黒白赤青紫金銀影夢愛心蝶".forEach { add(it.toString()) }
        // Common Korean syllables (surnames + frequent name syllables)
        "김이박최강고여우유리미호수아자하다라마바사나가".forEach { add(it.toString()) }
    }.distinct()
    // A fixed-per-process RANDOM permutation of the terms: the shared cursor walks this, so what we
    // grab "looks random" (not alphabetical a,b,c…) yet still covers EVERY term once per full pass,
    // and the mix of scripts is interleaved. Re-shuffled each process so repeated runs vary.
    private val CRAWL_ORDER: List<String> = CRAWL_TERMS.shuffled()
    private const val CRAWL_PAGE_GAP_MS = 1_500L   // pace avtrdb paging (polite to the DB)
    private const val CRAWL_TERM_GAP_MS = 3_000L
    private const val CRAWL_RL_BACKOFF = 5         // consecutive resolve failures => rate-limited
    // Shared across all crawling bots: the next term to hand out (partitions the term space with
    // zero overlap) + a cumulative "new avatars" counter for the status line.
    private val crawlTermCursor = java.util.concurrent.atomic.AtomicInteger(0)
    private val crawlNewTotal = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var crawlCursorLoaded = false

    /** Enable/disable the crawl (a mode the running per-slot loops pick up). No separate loop —
     *  the bots' slotLoops switch between role work and crawling based on this flag, so there's
     *  never a bot running a role AND a crawl at once. Called by BotController from the saved
     *  pref (forced off while paused / a login is in progress). */
    fun setAvtrdbCrawl(context: Context, enabled: Boolean) {
        if (enabled && !crawlCursorLoaded) {
            crawlCursorLoaded = true
            runCatching {
                crawlTermCursor.set(context.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE)
                    .getInt("avtrdb_crawl_idx", 0))
            }
        }
        avtrdbCrawlEnabled = enabled
        if (!enabled) { if (avtrdbCrawlStatus != "off") avtrdbCrawlStatus = "off"; crawlSeen.clear() }
    }

    // Avatar ids this crawl session already ATTEMPTED (resolved or skipped) — cross-page dedup
    // that also covers ids just contributed but not yet in the ~20-min avtar-id index. Cleared
    // when the crawl is turned off. Bounded implicitly by avtrdb's size.
    private val crawlSeen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** One crawl pass for ONE bot: take the next shared term, page avtrdb, resolve each NEW
     *  avatar (deduped vs catalog by avatar id — zero VRChat call for known ones — and claimed
     *  in `inFlight` so two bots never resolve the same one), contribute via the bot session. */
    private suspend fun avtrdbCrawlPass(context: Context, slot: Int): Boolean {
        val termN = crawlTermCursor.getAndIncrement()
        // Persist the cursor occasionally so a restart resumes roughly where it left off.
        if (termN % 4 == 0) runCatching {
            context.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE)
                .edit().putInt("avtrdb_crawl_idx", termN).apply()
        }
        val term = CRAWL_ORDER[((termN % CRAWL_ORDER.size) + CRAWL_ORDER.size) % CRAWL_ORDER.size]
        var new = 0; var nulls = 0; var page = 0
        crawl@ while (avtrdbCrawlEnabled && running && !paused && scope.isActive) {
            val pageResults = try { com.vrca.vrchat.AvatarSearch.searchPage(term, page) }
                catch (e: Throwable) { emptyList() }
            if (pageResults.isEmpty()) break
            // Batch the "already in catalog?" check for the WHOLE page at once (exact, parallel —
            // one edge GET per distinct bucket) instead of one serial check per candidate. No
            // VRChat call for known ones; only the genuinely-new get resolved below.
            val knownBatch = if (AvatarGlobalDb.shardWalkLive())
                AvatarGlobalDb.filterKnownSharded(context, pageResults.map { it.id }) else emptySet()
            for (r in pageResults) {
                if (!avtrdbCrawlEnabled || !running || paused || !scope.isActive) break@crawl
                if (!crawlSeen.add(r.id)) continue               // already attempted this session (any bot)
                val known = if (AvatarGlobalDb.shardWalkLive()) knownBatch.contains(r.id)
                            else AvatarGlobalDb.hasAvatarId(r.id)
                if (known) continue
                if (!inFlight.add(r.id)) continue                // another bot is resolving this one
                try {
                    val chk = BotVrchatSession.checkAvatar(context, slot, r.id)
                    if (chk == null) {
                        if (++nulls >= CRAWL_RL_BACKOFF) { avtrdbCrawlStatus = "bot ${slot + 1} rate-limited — pausing"; delay(30_000); break@crawl }
                        delay(PACE_MS); continue
                    }
                    nulls = 0
                    if (chk.alive && chk.fileId != null) {
                        // localInsert=false: the crawler bulk-adds thousands of OTHER avatars —
                        // keep them out of the local map so the admin stays memory-flat.
                        AvatarGlobalDb.contribute(context, chk.fileId, r.id, chk.name, chk.author, chk.authorId, chk.platforms, chk.description, localInsert = false)
                        new++
                        avtrdbCrawlStatus = "'$term' (bot ${slot + 1}) p$page: +$new · total +${crawlNewTotal.incrementAndGet()}"
                    }
                } finally { inFlight.remove(r.id) }
                delay(PACE_MS)
            }
            if (pageResults.size < com.vrca.vrchat.AvatarSearch.PAGE_SIZE) break   // last page
            page++
            delay(CRAWL_PAGE_GAP_MS)
        }
        return true
    }

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
        // Shard-walk mode has no whole-map to partition/count — the per-bot walk progress
        // (roleViews) is the live view during a blitz, so skip the map-scan blitz view.
        if (AvatarGlobalDb.shardWalkLive()) return emptyMap()
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

    /** Total avatars checked across all roles this session — the watchdog's progress signal. */
    fun totalChecked(): Int = progress.values.sumOf { it.checked }

    /** Force a CLEAN restart when the sweep is wedged (alive jobs that stopped progressing — e.g.
     *  after an app reopen, or a leaked claim). Unlike ensureRunning (which only restarts DEAD
     *  jobs), this rebuilds the loops AND clears the recently-processed guard so the fresh walk
     *  actually finds work. Cheap; the caller gates it behind a cooldown. */
    fun kick(context: Context, adminKey: String, manual: Map<Role, Int> = emptyMap()) {
        stop()                 // cancels jobs, clears inFlight (the "pause+start fixes it" reset)
        processedAt.clear()    // let the fresh loops re-evaluate work instead of skipping it
        start(context, adminKey, manual)
    }

    /** Kick a bounded full-catalog blitz: for the next window, FILL targets every
     *  incomplete entry and LIVENESS re-checks anything older than 1h — so all bots
     *  catch up the whole catalog (bios + dead checks) from before. Re-press to extend. */
    fun requestFullBlitz() {
        val now = System.currentTimeMillis()
        // Fresh blitz → anchor the cutoff to NOW so every currently-stale avatar is swept
        // exactly once. A re-press while one is already running only EXTENDS the window and
        // KEEPS the anchor, so it keeps draining the remainder instead of restarting the sweep.
        if (!blitzActive()) { blitzStartMs = now; blitzWalked.clear() }   // reset shard coverage on a fresh blitz
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
        val helping: String = "",
        /** Shards this bot has walked (shard-walk throughput — the real unit of work). */
        val shards: Int = 0,
        /** Reports the Reports bot has verified + culled (survives loaning to the walk). */
        val reportsVerified: Int = 0,
        val reportsRemoved: Int = 0,
        /** In walk mode a bot does BOTH first-fills AND refreshes; both are surfaced (M3) instead of
         *  only one via refreshedOrFilled. */
        val filled: Int = 0,
        val refreshed: Int = 0,
        /** True for the ONE folded row a bot shows in walk mode (all its roles chew the same pool, so
         *  per-role rows showed 0 for every role but the first — M2). */
        val walkFolded: Boolean = false,
        /** This bot IS handling reports (show the reports detail line). */
        val isReportsBot: Boolean = false
    )

    /** `pendingReports` = the Worker's live report count (from /health). Fill + liveness
     *  backlogs are computed LOCALLY from the cached catalog (one cheap pass). */
    fun roleViews(pendingReports: Int): List<RoleView> {
        lastPendingReports = pendingReports   // cached for the slot loop's Reports-bot loan decision
        var fill = 0; var liveness = 0
        if (AvatarGlobalDb.shardWalkLive()) {
            // SHARD-WALK MODE: no whole-map to scan. Both backlogs come from the tiny manifest
            // the rebuild Action writes (unfilled = Fill, staleCount = Liveness) — so the bots
            // show real queued numbers instead of a confusing 0 while they're clearly working.
            // Subtract the work done since the last manifest read so the numbers drop LIVE
            // (the manifest itself only refreshes every ~20 min). Clamped ≥0; the next manifest
            // read resets the baseline to the truth.
            fill = (manifestUnfilled - filledSinceManifest.get()).coerceAtLeast(0)
            liveness = (manifestStale - recheckedSinceManifest.get()).coerceAtLeast(0)
        } else {
            val snap = AvatarGlobalDb.snapshot()
            val cutoff = livenessCutoff()
            var la = 0; var lb = 0
            for (e in snap) {
                if (needsFill(e)) fill++
                if (e.checked < cutoff) { if (partitionOf(e.fileId) == 0) la++ else lb++ }
            }
            liveness = la + lb
        }
        lastTotalBacklog = fill + liveness + pendingReports

        // SHARD-WALK MODE: every bot pulls from ONE shared work-list (fill shards, then stale), so
        // they all chew the SAME combined pool together — the Fill/Liveness role labels are just
        // identity. Split the combined pool EVENLY across all the bots actually walking, so loaning
        // is distributed evenly (a Reports bot with pending reports is on its own queue instead;
        // everyone else — including a loaning Reports bot — gets an equal share).
        if (AvatarGlobalDb.shardWalkLive()) {
            val pool = fill + liveness
            // Group every ASSIGNED role by the SLOT (bot) it runs on, then emit ONE folded row per bot.
            // In walk mode slotLoop only drives progress on the slot's OWN (first) role — so every other
            // role sharing that slot showed checked/shards = 0 even while the bot was clearly working
            // (M2). Summing a slot's roles recovers the real counters (only the own role is non-zero).
            val bySlot = Role.values().mapNotNull { r ->
                val slot = progress.getValue(r).slot.takeIf { it >= 0 } ?: assignmentPreview[r]
                if (slot == null) null else slot to r
            }.groupBy({ it.first }, { it.second })
            val reportsSlot = if (pendingReports > 0) (progress.getValue(Role.REPORTS).slot.takeIf { it >= 0 }
                ?: assignmentPreview[Role.REPORTS]) else null
            val walkerSlots = bySlot.keys.count { it != reportsSlot }.coerceAtLeast(1)
            // L1: during a blitz the walk covers the WHOLE catalog, so the "queued" number should reflect
            // the remaining SHARD coverage (4096 − covered), not the steady-state manifest stale count —
            // otherwise the pill and the 4096-shard bar disagree.
            val blitzing = blitzActive()
            val displayPool = if (blitzing) (4096 - blitzWalked.size).coerceAtLeast(0) else pool
            val share = (displayPool + walkerSlots - 1) / walkerSlots   // ceil-split across the WALKING bots
            return bySlot.entries.sortedBy { it.key }.map { (slot, rolesOnSlot) ->
                var c = 0; var rem = 0; var fl = 0; var rf = 0; var sh = 0; var rv = 0; var rr = 0
                var status = ""; var run = false
                for (r in rolesOnSlot) {
                    val p = progress.getValue(r)
                    c += p.checked; rem += p.removed; fl += p.filled; rf += p.refreshed
                    sh += p.shards; rv += p.reportsVerified; rr += p.reportsRemoved
                    if (p.checked + p.shards > 0 || status.isEmpty()) status = p.status
                    run = run || p.running
                }
                val isRepBot = rolesOnSlot.contains(Role.REPORTS)
                RoleView(
                    role = if (isRepBot) Role.REPORTS else (rolesOnSlot.firstOrNull() ?: Role.FILL),
                    bot = "bot ${slot + 1}",
                    queued = if (slot == reportsSlot) pendingReports else share,
                    checked = c,
                    removed = rem,
                    refreshedOrFilled = fl + rf,   // legacy field: combined
                    filled = fl, refreshed = rf,
                    status = status,
                    running = run,
                    // L6: the Reports bot with no pending reports loans itself to the shard walk — surface
                    // that loan (it walks like everyone else) instead of leaving the computed value dead.
                    helping = if (isRepBot && slot != reportsSlot) "walk" else "",
                    shards = sh,
                    reportsVerified = rv,
                    reportsRemoved = rr,
                    walkFolded = true,
                    isReportsBot = isRepBot
                )
            }
        }

        // PRE-CUTOVER (local-map) mode: each role consumes its own backlog (or the one it's loaning
        // to), split evenly across the bots on that backlog.
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
                helping = p.helping,
                shards = p.shards,
                reportsVerified = p.reportsVerified,
                reportsRemoved = p.reportsRemoved
            )
        }
    }

    // ---- the per-slot loop ---------------------------------------------------

    private suspend fun slotLoop(context: Context, adminKey: String, slot: Int, roles: List<Role>, liveIndex: Int, liveCount: Int) {
        val ownRole = roles.firstOrNull()
        while (running && scope.isActive) {
            // HARD PAUSE: do nothing (no VRChat call, no counter touch, no alive-stamp) while
            // paused, so the counters stay frozen the instant the admin pauses — regardless of
            // whether stop()/cancellation has landed yet. Resumes the moment pause clears.
            if (paused) { delay(1000); continue }
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
            // Capture the two mode flags for THIS cycle. A cycle does EXACTLY ONE kind of
            // work — crawl, blitz, or roles — so a bot never runs a role AND a crawl at once
            // (the session double-load / collision fix). The responsive sleep below wakes the
            // instant either flag flips, so toggling crawl kicks every bot into/out of it fast.
            val crawling = avtrdbCrawlEnabled
            try {
                var did = false
                val blitzing = blitzActive()
                if (crawling) {
                    // CRAWL MODE: this bot pulls the next shared avtrdb term and digests it.
                    // Roles/blitz are skipped this cycle → no collision. All logged-in bots do
                    // this in parallel (distinct terms via the shared cursor).
                    did = avtrdbCrawlPass(context, slot)
                    roles.forEach { progress.getValue(it).status = "crawling avtrdb" }
                } else if (AvatarGlobalDb.shardWalkLive()) {
                    // SHARD-WALK MODE (post-cutover): memory-flat, no whole-master grab. Every
                    // bot pulls the next shard from a shared cursor and fills unfilled + rechecks
                    // stale entries in it; reports stay real-time (one bot polls). Blitz just
                    // widens the recheck cutoff (livenessCutoff()), so the same walk catches up
                    // everything. This is the future-proof path — it never loads the whole catalog.
                    val r = ownRole ?: Role.FILL
                    var didReports = false
                    if (roles.contains(Role.REPORTS)) { didReports = reportsPass(context, adminKey, slot, r); did = didReports || did }
                    // The Reports bot has no queue of its own most of the time (reports are rare),
                    // so it LOANS itself to the shared catalog walk. Reflect that so its row shows a
                    // real share of the backlog it's helping (not a confusing "queued 0") and re-splits
                    // the moment reports arrive. Fill/Liveness bots stay on their own field.
                    if (r == Role.REPORTS) progress.getValue(r).helping =
                        if (didReports || lastPendingReports > 0) ""
                        else if (manifestUnfilled >= manifestStale) "Fill" else "Liveness"
                    did = walkPass(context, adminKey, slot, r) || did
                } else {
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
                }
                if (!running) break
                // Responsive sleep: wake IMMEDIATELY (within 500ms) if the blitz OR crawl state
                // flips, so toggling either kicks EVERY bot at once instead of each waiting out
                // its idle sleep. Crawl uses the short term-gap (it always has work).
                // Keep moving through a non-empty work-list with the short pause even when a
                // given shard turned out clear (already filled by another bot / stale worklist),
                // so the backlog is chewed through in minutes instead of one shard per 20s.
                val target = if (crawling) CRAWL_TERM_GAP_MS
                    else if (did || blitzActive() || (AvatarGlobalDb.shardWalkLive() && workRemaining())) ACTIVE_PAUSE_MS
                    else IDLE_SLEEP_MS
                var slept = 0L
                while (slept < target && running && scope.isActive &&
                       blitzActive() == blitzing && avtrdbCrawlEnabled == crawling) {
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
            if (!running || paused) break
            val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
            p.checked++
            if (chk == null) {
                if (needsFill(e)) noteFillNull(slot, e.fileId)   // poison-entry strike (fill items only)
                delay(PACE_MS); continue
            }
            clearFillAttempt(e.fileId)
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

    // ---- poison-entry guard (fixes the "To process / queued stuck at N" bug) -----
    // An avatar whose VRChat check keeps returning null — never a definitive alive (to
    // fill) or 404/410 (to remove), e.g. a persistent 5xx/redirect/odd response — would
    // sit in the Fill backlog FOREVER: filled never flips true, so needsFill stays true,
    // so it pins "To process / queued" at a nonzero value even when every other entry is
    // done. Count consecutive null checks per file id and, once past a threshold, drop it
    // from the Fill queue for this process so the backlog drains. Strictly guarded:
    //  - only counted while the bot SESSION is proven alive (a recent authenticated
    //    success on this slot), so a global rate-limit / dead session never benches a
    //    perfectly-good entry;
    //  - cleared on ANY definitive result, so a transient blip never sticks;
    //  - in-memory only, so the next admin restart retries the entry (and the 7-day
    //    liveness sweep still covers it meanwhile).
    private val fillAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val fillGaveUp = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private const val FILL_GIVEUP_ATTEMPTS = 6
    private const val SESSION_ALIVE_WINDOW_MS = 6L * 60 * 1000
    private fun sessionAlive(slot: Int): Boolean {
        val t = BotVrchatSession.lastAuthOkMs(slot)
        return t > 0 && System.currentTimeMillis() - t < SESSION_ALIVE_WINDOW_MS
    }
    /** Count a null check against a fill entry as a poison strike (only when the session
     *  is proven alive); bench it once it exceeds the threshold. */
    private fun noteFillNull(slot: Int, fileId: String) {
        if (!sessionAlive(slot)) return
        val n = (fillAttempts[fileId] ?: 0) + 1
        if (n >= FILL_GIVEUP_ATTEMPTS) { fillGaveUp.add(fileId); fillAttempts.remove(fileId) }
        else fillAttempts[fileId] = n
    }
    private fun clearFillAttempt(fileId: String) { fillAttempts.remove(fileId) }

    // ---- shard-walk (memory-flat bots, post-cutover) -------------------------
    private const val WALK_BATCH = 40
    // Work-list driven shard walk: the rebuild Action publishes `_worklist.json` = the exact
    // shard prefixes that hold fill/stale work, so the bots walk ONLY those instead of blindly
    // stepping through all 4096 (most empty → counters barely move). Bots share ONE queue
    // (partitioned with zero overlap by polling). When it drains, one bot refills it from a
    // fresh worklist; a genuinely empty worklist means no backlog → idle. If the worklist can't
    // be read, fall back to the old blind cursor so liveness coverage never fully stops.
    private val workQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val worklistMutex = Mutex()
    @Volatile private var worklistFetchedMs = 0L
    @Volatile private var worklistEmpty = false        // read OK but no work → idle (don't blind-walk)
    private const val WORKLIST_TTL_MS = 60_000L

    // ---- per-shard "last swept" ordering (local, persisted) ------------------
    // The Action's work-list drives FILL + known-stale promptly; this drives an even LIVENESS
    // cadence. Each shard remembers when a bot last walked it; the idle/fallback picks the
    // OLDEST-swept shard whose sweep is older than the weekly interval, so shards come due on
    // staggered cycles (no Action-driven wave) — and the per-avatar `checked` gate still means a
    // freshly-verified shard costs only a cheap edge-cached read, no VRChat calls.
    private val sweptAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var sweptLoaded = false
    private val sweptPersistCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private const val LIVENESS_SHARD_INTERVAL_MS = 30L * 24 * 60 * 60_000L  // re-sweep a shard ~monthly (was weekly; 4x cheaper)
    private fun loadSwept(context: Context) {
        if (sweptLoaded) return
        sweptLoaded = true
        runCatching {
            val s = context.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE)
                .getString("shard_swept", "{}") ?: "{}"
            val j = org.json.JSONObject(s)
            val it = j.keys()
            while (it.hasNext()) { val k = it.next(); sweptAt[k] = j.optLong(k, 0L) }
        }
    }
    private fun stampSwept(context: Context, prefix: String) {
        sweptAt[prefix] = System.currentTimeMillis()
        if (sweptPersistCounter.incrementAndGet() % 16 == 0) runCatching {
            val j = org.json.JSONObject(); for ((k, v) in sweptAt) j.put(k, v)
            context.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE)
                .edit().putString("shard_swept", j.toString()).apply()
        }
    }
    /** Oldest-swept shard prefix (unswept counts as oldest). `dueOnly` skips shards swept within
     *  the weekly interval (returns null when nothing is due → idle). Stamps the pick to claim it,
     *  so two bots rarely grab the same shard (the per-avatar claim/dedup covers any overlap). */
    private val sweptSelectLock = Any()
    // Short-lived per-shard CLAIM so two bots don't grab the same shard at once, WITHOUT stamping
    // sweptAt at selection time. The old code stamped sweptAt on selection — so a shard whose read
    // then FAILED was marked "swept just now" and hidden from the liveness sweep for the full 30d
    // interval (a silent liveness gap on any flaky read). Now sweptAt is stamped only AFTER a
    // successful read (in walkPass); a failed read leaves the claim to EXPIRE (a ~30s backoff) and
    // the shard stays oldest, so it's retried instead of hidden.
    private val shardClaims = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val SHARD_CLAIM_TTL_MS = 30_000L
    fun releaseShardClaim(prefix: String) { shardClaims.remove(prefix) }
    /** Oldest-swept shard prefix (unswept counts as oldest). `dueOnly` skips shards swept within the
     *  liveness interval (returns null when nothing is due → idle). `coverBeforeMs` bounds a PASS: only
     *  shards last swept BEFORE that instant are eligible, so a blitz / fill-scan ENDS once every shard
     *  has been covered (no endless re-reading of an already-covered catalog). Claims the pick so two
     *  bots rarely grab the same shard; the claim (not sweptAt) is what's set here. */
    private fun oldestSweptPrefix(context: Context, dueOnly: Boolean, coverBeforeMs: Long? = null): String? {
        loadSwept(context)
        synchronized(sweptSelectLock) {
            val now = System.currentTimeMillis()
            var best: String? = null; var bestT = Long.MAX_VALUE
            for (i in 0 until 4096) {
                val p = i.toString(16).padStart(3, '0')
                val t = sweptAt[p] ?: 0L
                if (dueOnly && now - t < LIVENESS_SHARD_INTERVAL_MS) continue
                if (coverBeforeMs != null && t >= coverBeforeMs) continue   // already covered this pass
                val claim = shardClaims[p]; if (claim != null && now - claim < SHARD_CLAIM_TTL_MS) continue
                if (t < bestT) { bestT = t; best = p }
            }
            best?.let { shardClaims[it] = now }   // claim (NOT sweptAt) — stamped on success in walkPass
            return best
        }
    }
    /** Oldest shard eligible for FILL work: the whole catalog while a fill backlog is known, bounded
     *  to ONE pass per manifest-value (fillScanStartMs) so a STICKY manifestUnfilled > 0 (poison
     *  entries) can't make the bots blind-walk all 4096 shards forever; after one pass it falls back
     *  to the due (liveness) trickle. */
    private fun fillOrLivenessPrefix(context: Context, fillBacklog: Boolean): String? {
        if (fillBacklog) {
            if (fillScanStartMs == 0L) fillScanStartMs = System.currentTimeMillis()
            oldestSweptPrefix(context, dueOnly = false, coverBeforeMs = fillScanStartMs)?.let { return it }
        }
        return oldestSweptPrefix(context, dueOnly = true)
    }
    /** True while there are still queued shards to walk — slotLoop uses a SHORT pause then
     *  (keep moving through the backlog) instead of the 20s idle sleep. */
    fun workRemaining(): Boolean = workQueue.isNotEmpty()

    // Distinct shard prefixes walked during the CURRENT blitz — so the Bots tab can show
    // "N / 4096 shards checked" (done + left). Cleared on a fresh blitz.
    private val blitzWalked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Blitz shard coverage (done, total=4096) while a blitz is active, else null. */
    fun blitzShardProgress(): Pair<Int, Int>? = if (blitzActive()) blitzWalked.size to 4096 else null

    /** Next shard prefix to walk, or null when there is genuinely no backlog (idle). During a
     *  BLITZ, walk ALL 4096 shards (blind cursor) so "check entire catalog" actually covers it —
     *  the work-list only lists steady-state work shards. Otherwise refill the shared queue from
     *  `_worklist.json` when empty (single-flight, TTL-throttled). */
    private suspend fun nextWorkPrefix(context: Context): String? {
        // BLITZ: cover the WHOLE catalog ONCE, oldest-swept first, bounded to shards not yet swept
        // since the blitz began (blitzStartMs). Returns null once every shard is covered → the bots
        // idle → the blitz keepalive lapses → the blitz ENDS, instead of spin-reading the whole
        // catalog every ~1.5s for the rest of the 30-min window after coverage was already complete.
        if (blitzActive()) return oldestSweptPrefix(context, dueOnly = false, coverBeforeMs = blitzStartMs)
        workQueue.poll()?.let { return it }
        // When the manifest reports a real FILL backlog (unfilled avatars exist) but the
        // worklist is empty, those unfilled entries live in shards NOT touched by recent
        // contributions — so the Worker's incremental `_worklist.json` never lists them, AND
        // a recent walk/blitz stamped every shard "swept < 7 days ago" so the due-only liveness
        // trickle returns null → the bots go IDLE with a nonzero backlog (the "queued 368,
        // checked 0" bug). While a fill backlog is known, walk the WHOLE catalog oldest-swept
        // first (dueOnly=false) so walkPass's needsFill() filter finds and fills them; it
        // reverts to the weekly liveness trickle once the backlog drains to 0.
        val fillBacklog = manifestUnfilled > 0
        return worklistMutex.withLock {
            workQueue.poll()?.let { return@withLock it }   // another bot refilled while we waited
            // If we recently learned the worklist is empty, don't re-hit the CDN this cycle — but
            // instead of idling, walk oldest-swept: the WHOLE catalog while a fill backlog is
            // known (find the unfilled), else just the due (weekly) shards (liveness trickle).
            if (worklistEmpty && System.currentTimeMillis() - worklistFetchedMs < WORKLIST_TTL_MS)
                return@withLock fillOrLivenessPrefix(context, fillBacklog)
            val wl = AvatarGlobalDb.fetchWorklist(context)
            worklistFetchedMs = System.currentTimeMillis()
            if (wl == null) { worklistEmpty = false; return@withLock fillOrLivenessPrefix(context, fillBacklog) }  // read failed → bounded fallback
            val (fill, stale) = wl
            worklistEmpty = fill.isEmpty() && stale.isEmpty()
            workQueue.addAll(fill); workQueue.addAll(stale)   // fill first, then stale (liveness)
            // Worklist empty → oldest-swept: bounded whole-catalog pass while a fill backlog is known,
            // else the due (liveness) trickle.
            workQueue.poll() ?: fillOrLivenessPrefix(context, fillBacklog)
        }
    }
    // Skip re-checking an avatar within the flush-lag window: the bot's fill/check reaches the
    // shard ~1 flush cycle later, so without this a fast re-walk of the same shard would re-do
    // the same VRChat call. Bounded + TTL'd.
    private val processedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // Must SPAN the rebuild Action cadence (~20 min): a re-verified entry's fresh `checked` only
    // reaches the shards on the next rebuild, so until then `fetchCatalogShard` still reports it
    // stale. At 4 min the bots re-issued the SAME checkAvatar every cycle for ~16 min (and a blitz
    // on a caught-up catalog re-swept it ~5×). 25 min covers the whole window so each avatar is
    // re-checked at most once per rebuild. Cap raised to hold a busy/blitz window without evicting early.
    private const val PROCESSED_TTL_MS = 25 * 60_000L
    private fun wasRecentlyProcessed(fileId: String): Boolean {
        val t = processedAt[fileId] ?: return false
        if (System.currentTimeMillis() - t < PROCESSED_TTL_MS) return true
        processedAt.remove(fileId); return false
    }
    private fun markProcessed(fileId: String) {
        processedAt[fileId] = System.currentTimeMillis()
        if (processedAt.size > 60000) {
            val cut = System.currentTimeMillis() - PROCESSED_TTL_MS
            processedAt.entries.removeAll { it.value < cut }
        }
    }
    /** Total unfilled backlog from the rebuild Action's `_manifest.json` (set by BotController).
     *  -1 = not read yet. Used for the Bots-tab backlog in shard-walk mode (no whole-map scan). */
    @Volatile private var lastPendingReports = 0   // last /health report count (from roleViews), for the loan decision
    // Optimistic real-time backlog decrement: the manifest counts only refresh every ~20 min (Action
    // cadence), so the raw queued numbers sat flat between rebuilds even while bots were working.
    // These count work done SINCE the last manifest read and are subtracted from the displayed
    // backlog, so "queued"/"to process" drop live; each manifest refresh resets them to the truth.
    private val filledSinceManifest = java.util.concurrent.atomic.AtomicInteger(0)
    private val recheckedSinceManifest = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile var manifestUnfilled = -1; private set
    // Start of the CURRENT bounded fill-scan pass — reset whenever manifestUnfilled changes to a new
    // value, so each genuine backlog change gets ONE fresh whole-catalog pass; a sticky value doesn't
    // re-trigger endless passes (H2). fillOrLivenessPrefix bounds the pass to shards swept before this.
    @Volatile private var fillScanStartMs = 0L
    // Reset the optimistic decrement ONLY when the value actually CHANGES (a fresh Action rebuild),
    // not on every 30s poll of the same value — otherwise each poll snapped the live-decremented
    // queue back up to the manifest number (the "keeps jumping to ~8200" sawtooth).
    fun setManifestUnfilled(n: Int) {
        if (n == manifestUnfilled) return
        val grew = n > manifestUnfilled     // manifestUnfilled starts at -1, so the first real value "grows"
        manifestUnfilled = n
        filledSinceManifest.set(0)
        // Start a fresh whole-catalog fill pass ONLY when the backlog GREW (genuinely new unfilled work
        // arrived) — NOT when it shrank as the bots drain it. The old "reset on any change" restarted the
        // bounded pass every few minutes (the count moves as bots fill), so a pass never completed and the
        // walk re-covered the whole catalog forever ("shard walk running for days, never finished"). A
        // draining count now lets the in-progress pass finish, then falls to the idle liveness trickle.
        if (n > 0 && grew) fillScanStartMs = System.currentTimeMillis()
    }
    /** Liveness backlog (entries due a recheck) from the manifest — so the Liveness bots show a
     *  real "queued" number in shard-walk mode instead of a confusing 0 while they're working. */
    @Volatile var manifestStale = -1; private set
    fun setManifestStale(n: Int) { if (n != manifestStale) { manifestStale = n; recheckedSinceManifest.set(0) } }

    /** WALK one shard (post-cutover): read it, fill the unfilled + recheck the stale, push ops.
     *  Memory holds only this shard. Dead-checks obey the same VRChat-outage guard as the
     *  master-based passes. Returns true if it did VRChat work. */
    private suspend fun walkPass(context: Context, adminKey: String, slot: Int, role: Role): Boolean {
        if (paused) return false
        val p = progress.getValue(role)
        val prefix = nextWorkPrefix(context) ?: run { p.status = "no backlog — idle"; return false }
        val entries = AvatarGlobalDb.fetchCatalogShard(context, prefix)
        // Failed read → leave the claim to EXPIRE (a ~30s backoff) so the shard stays oldest and is
        // RETRIED, and do NOT stamp sweptAt / advance the coverage bar (M1 — a flaky read must not hide
        // a shard for 30d nor falsely count it as covered).
        if (entries == null) { p.status = "walk $prefix: shard read failed"; return false }
        stampSwept(context, prefix)   // success: record the sweep (even if clean) so oldest-swept advances
        releaseShardClaim(prefix)     // done with it — let it be re-selected once genuinely due again
        if (blitzActive()) blitzWalked.add(prefix)   // count coverage only for shards actually READ
        p.shards++                    // shard-walk throughput (one shard = one cheap grouped R2 write)
        val cutoff = livenessCutoff()
        val work = entries.filter { (needsFill(it) || it.checked < cutoff) && !wasRecentlyProcessed(it.fileId) }
        if (work.isEmpty()) { p.status = "walking ($prefix clear)"; return false }
        val batch = claimBatch(work, WALK_BATCH)
        if (batch.isEmpty()) return false
        if (blitzActive()) blitzUntilMs = maxOf(blitzUntilMs, System.currentTimeMillis() + BLITZ_KEEPALIVE_MS)
        p.status = "walk $prefix: ${batch.size}"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val okChecked = mutableListOf<String>()
        try {
            var nulls = 0
            var rateLimited = false
            for (e in batch) {
                if (!running || paused) break
                val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
                p.checked++
                if (chk == null) {
                    if (needsFill(e)) {
                        noteFillNull(slot, e.fileId)
                        // Just benched (6 nulls while the session was proven alive) → mark it
                        // filled=true on the server so it LEAVES the unfilled count. Otherwise a
                        // permanently-unfetchable entry pins manifestUnfilled > 0 forever, which
                        // (with the fill-backlog whole-catalog walk above) would churn the bots
                        // across all shards endlessly. The weekly liveness sweep still re-checks it.
                        if (fillGaveUp.contains(e.fileId)) {
                            upserts.add(e.copy(filled = true)); filledSinceManifest.incrementAndGet()
                        }
                    }
                    if (++nulls >= 3) { p.status = "rate-limited — backing off"; rateLimited = true; break }
                    delay(PACE_MS); continue
                }
                nulls = 0; clearFillAttempt(e.fileId); markProcessed(e.fileId)
                if (chk.alive) noteAlive()
                // AUTHOR RENAME: a live avatar whose authorId matches the stored one but whose author
                // NAME differs = the creator renamed. Authoritative (VRChat GET). Queue a catalog-wide
                // rename (the Worker applies it to ALL their avatars); the local liveRefresh below still
                // fixes THIS entry immediately.
                if (chk.alive && chk.authorId.isNotBlank() && chk.authorId == e.authorId &&
                    chk.author.isNotBlank() && chk.author != e.author) {
                    pendingAuthorRenames[chk.authorId] = chk.author
                }
                if (!chk.alive) {
                    if (canRemove()) { removes.add(e.fileId); p.removed++
                        // L7: an entry can be BOTH unfilled and stale — decrement whichever backlog(s)
                        // it counted against, not just one, so the liveness "queued" can reach 0.
                        if (needsFill(e)) filledSinceManifest.incrementAndGet()
                        if (e.checked < cutoff) recheckedSinceManifest.incrementAndGet() }
                    // else: suspected VRChat outage — defer the removal, next walk retries
                } else if (needsFill(e)) {
                    val upd = fillRefresh(e, chk)
                    if (upd.fileId != e.fileId) removes.add(e.fileId)   // re-key on image change
                    upserts.add(upd); p.filled++; filledSinceManifest.incrementAndGet()
                    if (e.checked < cutoff) recheckedSinceManifest.incrementAndGet()   // L7: also cleared a stale entry
                } else {
                    val upd = liveRefresh(e, chk)
                    if (upd != null) { if (upd.fileId != e.fileId) removes.add(e.fileId); upserts.add(upd); p.refreshed++ }
                    else okChecked.add(e.fileId)
                    recheckedSinceManifest.incrementAndGet()   // a stale entry got re-verified either way
                }
                delay(PACE_MS)
            }
            if (upserts.isNotEmpty() || removes.isNotEmpty() || okChecked.isNotEmpty())
                pushOps(context, adminKey, upserts.toList(), removes.toList(), emptyList(), okChecked.toList())
            p.status = "walk $prefix: filled=${p.filled} checked=${p.checked} removed=${p.removed}"
            // On a rate-limit break, return false so slotLoop takes the IDLE sleep (not the 1.5s
            // active pause) — otherwise all 4 bots keep firing 3-call bursts every ~5s into an
            // already-throttled endpoint and the throttle never clears.
            return !rateLimited
        } finally { release(batch.map { it.fileId }) }
    }

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
        // Also drop any queued `checked` for the same fileId — an upsert already persists (and bumps
        // checked server-side), so a separate checked op for it in the same flush is redundant (L4).
        for (e in upserts) { pendingRemoves.remove(e.fileId); pendingChecked.remove(e.fileId); pendingUpserts[e.fileId] = e }
        for (fid in removes) { pendingUpserts.remove(fid); pendingChecked.remove(fid); pendingRemoves.add(fid) }
        for (fid in clears) pendingClears.add(fid)
        for (fid in checked) if (!pendingUpserts.containsKey(fid) && !pendingRemoves.contains(fid)) pendingChecked.add(fid)
        // Hit the 1k buffer cap → flush NOW instead of waiting out FLUSH_MS (bounds buffer + latency).
        // capFlushing gates to one in-flight flush; flushPending is mutex-guarded so it's safe + idempotent.
        val total = pendingUpserts.size + pendingRemoves.size + pendingChecked.size + pendingClears.size
        if (total >= BOT_FLUSH_MAX_BATCH && capFlushing.compareAndSet(false, true)) {
            scope.launch { try { flushPending() } catch (_: Throwable) {} finally { capFlushing.set(false) } }
        }
    }

    /** Drain the buffered ops to the Worker in size-capped chunks (≈ one KV write each),
     *  re-queuing anything that fails so nothing is lost. Called on the FLUSH_MS timer,
     *  so KV writes scale with TIME, not with how fast the bots process avatars. */
    private suspend fun flushPending() {
        val ctx = flushCtx ?: return
        if (flushKey.isBlank()) return
        flushMutex.withLock {
            // Snapshot the keys, then remove EXACTLY those — never clear() — so ops enqueued
            // concurrently (enqueueOps runs on the bot threads, without this mutex) aren't wiped
            // between the snapshot and a clear and silently dropped from the Worker push.
            val upsertKeys = pendingUpserts.keys.toList()
            val upserts = ArrayDeque(upsertKeys.mapNotNull { pendingUpserts.remove(it) })
            val removeKeys = pendingRemoves.toList(); pendingRemoves.removeAll(removeKeys.toSet())
            val removes = ArrayDeque(removeKeys)
            val clearKeys = pendingClears.toList(); pendingClears.removeAll(clearKeys.toSet())
            val clears = ArrayDeque(clearKeys)
            val checkedKeys = pendingChecked.toList(); pendingChecked.removeAll(checkedKeys.toSet())
            val checked = ArrayDeque(checkedKeys)
            // Snapshot author renames the same way (remove exactly the snapshotted keys, never clear()).
            val renameKeys = pendingAuthorRenames.keys.toList()
            val renames = HashMap<String, String>().apply { renameKeys.forEach { k -> pendingAuthorRenames.remove(k)?.let { put(k, it) } } }
            var renamesSent = renames.isEmpty()
            if (upserts.isEmpty() && removes.isEmpty() && clears.isEmpty() && checked.isEmpty() && renames.isEmpty()) { pushError = ""; return }
            // FREE batch-contents readout: capture the counts + a few names BEFORE the deques are
            // drained, so the admin can see what just went up (no network — it's already in hand).
            val flAdd = upserts.size; val flRem = removes.size; val flChk = checked.size
            val flNames = upserts.mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }.take(4)
            var failed = false
            while (upserts.isNotEmpty() || removes.isNotEmpty() || clears.isNotEmpty() || checked.isNotEmpty()) {
                val u = ArrayList<AvatarGlobalDb.Entry>(); repeat(FLUSH_CHUNK) { upserts.removeFirstOrNull()?.let(u::add) }
                val r = ArrayList<String>(); repeat(FLUSH_CHUNK) { removes.removeFirstOrNull()?.let(r::add) }
                val c = ArrayList<String>(); repeat(FLUSH_CHUNK) { clears.removeFirstOrNull()?.let(c::add) }
                val k = ArrayList<String>(); repeat(FLUSH_CHUNK) { checked.removeFirstOrNull()?.let(k::add) }
                if (u.isEmpty() && r.isEmpty() && c.isEmpty() && k.isEmpty()) break
                // Piggyback the (rare, small) author renames on the FIRST chunk — one push, one KV write.
                val rn = if (!renamesSent) renames else emptyMap()
                if (!AvatarGlobalDb.adminPush(ctx, flushKey, u, r, c, k, rn)) {
                    // Re-queue this chunk + everything still pending, retry next flush.
                    (u + upserts).forEach { pendingUpserts.putIfAbsent(it.fileId, it) }
                    (r + removes).forEach { pendingRemoves.add(it) }
                    (c + clears).forEach { pendingClears.add(it) }
                    (k + checked).forEach { if (!pendingUpserts.containsKey(it) && !pendingRemoves.contains(it)) pendingChecked.add(it) }
                    if (!renamesSent) renames.forEach { (a, n) -> pendingAuthorRenames.putIfAbsent(a, n) }
                    failed = true; break
                }
                renamesSent = true
            }
            // Renames pending but no ops chunk carried them (ops were all empty) → one standalone push.
            if (!failed && !renamesSent && renames.isNotEmpty()) {
                if (!AvatarGlobalDb.adminPush(ctx, flushKey, emptyList(), emptyList(), emptyList(), emptyList(), renames)) {
                    renames.forEach { (a, n) -> pendingAuthorRenames.putIfAbsent(a, n) }
                    failed = true
                }
            }
            pushError = if (failed) "PUSH REJECTED — set ADMIN_KEY as a Secret on Cloudflare matching the app key" else ""
            if (!failed) {
                val names = if (flNames.isEmpty()) "" else " · " + flNames.joinToString(", ") +
                    (if (flAdd > flNames.size) " +${flAdd - flNames.size} more" else "")
                lastFlushInfo = "+$flAdd new  ✓$flChk checked  −$flRem removed$names"
                lastFlushAtMs = System.currentTimeMillis()
            }
        }
    }
    /** FREE readout of the bots' most recent push to the Worker (counts + sample names) — the
     *  admin can see WHAT just went up without any extra network/cost (it's the flush buffer). */
    @Volatile var lastFlushInfo = ""; private set
    @Volatile var lastFlushAtMs = 0L; private set

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
    // Also excludes benched poison entries (see the poison-entry guard) so one perpetually
    // null-checking avatar can't pin the Fill backlog at a nonzero value forever.
    private fun needsFill(e: AvatarGlobalDb.Entry): Boolean = !e.filled && !fillGaveUp.contains(e.fileId)

    private fun partitionOf(fileId: String): Int = (fileId.hashCode() and 0x7fffffff) % 2

    // ---- roles ---------------------------------------------------------------

    private suspend fun reportsPass(context: Context, adminKey: String, slot: Int, role: Role): Boolean {
        val p = progress.getValue(role)
        // Use the count BotController already polls (cached in lastPendingReports) instead of a
        // fresh /health GET every cycle — the reports bot idle-walks clear shards ~every 1.5–20s,
        // so this avoided a steady stream of redundant /health reads for a value we already have.
        if (lastPendingReports <= 0) { p.status = "no reports — idle"; return false }
        val reports = AvatarGlobalDb.fetchReports(adminKey)
        if (reports.isEmpty()) { p.status = "no reports — idle"; return false }
        p.status = "verifying ${reports.size} report(s)"
        val upserts = mutableListOf<AvatarGlobalDb.Entry>()
        val removes = mutableListOf<String>()
        val clears = mutableListOf<String>()
        for (r in reports) {
            if (!running || paused) break
            if (r.avatarId.isBlank()) { clears.add(r.fileId); continue }
            val chk = BotVrchatSession.checkAvatar(context, slot, r.avatarId)
            // Count a verification only when the check ACTUALLY ran — a null is a transient/rate-limited
            // no-op that verified nothing, so it must not inflate checked/reportsVerified (M4).
            if (chk == null) { delay(PACE_MS); continue }
            p.checked++; p.reportsVerified++   // dedicated report counter (survives loaning to the walk)
            if (chk.alive) noteAlive()
            if (!chk.alive) {
                // Don't remove on a report during a suspected VRChat outage — a 404 could be
                // false. Skip clearing too, so the report stays pending and is re-verified once
                // VRChat is back (don't mark a maybe-alive avatar's report resolved).
                // On a confirmed cull, CLEAR the report in the SAME push as the remove so it doesn't
                // linger pending and get needlessly re-fetched + re-checked for another cycle or two
                // before the Worker's own repShardEntries sweep drops it (M4).
                if (canRemove()) { removes.add(r.fileId); clears.add(r.fileId); p.removed++; p.reportsRemoved++; applyItemLocal(remove = r.fileId) }
            }
            else {
                clears.add(r.fileId)  // alive → false positive
                val cur = AvatarGlobalDb.lookup(r.fileId)
                if (cur != null) {
                    // In the local map (pre-cutover / rare): diff-refresh only if something changed.
                    liveRefresh(cur, chk)?.let { upd ->
                        if (upd.fileId != cur.fileId) removes.add(cur.fileId)
                        upserts.add(upd); p.refreshed++
                        applyItemLocal(upd = upd, rekeyFrom = if (upd.fileId != cur.fileId) cur.fileId else null)
                    } ?: applyItemLocal(checked = r.fileId)  // alive + identical → bump last-checked
                } else if (chk.name.isNotBlank()) {
                    // MEMORY-FLAT (post-cutover): we don't hold the old entry, so push the FRESH
                    // VRChat data directly — a RENAMED reported avatar gets its new name/author/
                    // platforms/bio updated (was silently dropped when lookup returned null).
                    // Gated on a real name so a blank public-avatar response can't blank a good name.
                    val fresh = AvatarGlobalDb.Entry(
                        fileId = chk.fileId ?: r.fileId, avatarId = r.avatarId, name = chk.name,
                        author = chk.author, authorId = chk.authorId, platforms = chk.platforms,
                        checked = System.currentTimeMillis(), description = chk.description,
                        perfPc = chk.perfPc, perfQuest = chk.perfQuest, perfIos = chk.perfIos, filled = true
                    )
                    if (chk.fileId != null && chk.fileId != r.fileId) removes.add(r.fileId)  // image re-keyed
                    upserts.add(fresh); p.refreshed++
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
                if (!running || paused) break
                val chk = BotVrchatSession.checkAvatar(context, slot, e.avatarId)
                p.checked++
                if (chk == null) {
                    noteFillNull(slot, e.fileId)   // poison-entry strike (only if session is alive)
                    if (++nulls >= 3) { p.status = "rate-limited — backing off"; break }
                    delay(PACE_MS); continue
                }
                nulls = 0
                clearFillAttempt(e.fileId)         // definitive result → reset the poison strike
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
                if (!running || paused) break
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
