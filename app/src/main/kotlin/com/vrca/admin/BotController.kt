package com.vrca.admin

import android.content.Context
import com.vrca.vrchat.AvatarGlobalDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PROCESS-LIFETIME owner of the bots' UI state + polling. The admin Bots tab only
 * COLLECTS from here — it never runs its own network/validation effects — so scrolling
 * a LazyColumn (which recycles items) or switching tabs can't reset a bot to "checking"
 * or spam `/auth/user`. One staggered loop validates each slot and auto-re-logins an
 * expired one; the backlog/progress views are computed OFF the main thread.
 */
object BotController {
    data class BotUi(
        val slot: Int,
        val loggedIn: Boolean,
        val name: String,
        val auth: BotVrchatSession.Auth
    )

    private val _bots = MutableStateFlow(
        (0 until BotVrchatSession.SLOTS).map { BotUi(it, false, "", BotVrchatSession.Auth.UNKNOWN) }
    )
    val bots: StateFlow<List<BotUi>> = _bots

    private val _views = MutableStateFlow<List<AvatarCatalogSweep.RoleView>>(emptyList())
    val views: StateFlow<List<AvatarCatalogSweep.RoleView>> = _views

    private val _totalQueued = MutableStateFlow(0)
    val totalQueued: StateFlow<Int> = _totalQueued

    private val _blitz = MutableStateFlow(false)
    val blitz: StateFlow<Boolean> = _blitz

    private val _blitzViews = MutableStateFlow<Map<Int, AvatarCatalogSweep.BlitzView>>(emptyMap())
    val blitzViews: StateFlow<Map<Int, AvatarCatalogSweep.BlitzView>> = _blitzViews

    /** Proof-of-life for the sweep loop: true while it has cycled within the last minute
     *  (alive even when idle/caught-up), and how many ms since the last cycle (-1 = never).
     *  Lets the Bots tab show "running (idle)" vs "stopped" when the backlog is flat. */
    private val _sweepAlive = MutableStateFlow(false)
    val sweepAlive: StateFlow<Boolean> = _sweepAlive
    private val _sweepLastCycleAgoMs = MutableStateFlow(-1L)
    val sweepLastCycleAgoMs: StateFlow<Long> = _sweepLastCycleAgoMs

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // A bot that made a successful authed API call within this window is shown "Authed" even
    // if the periodic validate hasn't run / returned UNKNOWN. Covers the slow-validate gap.
    private const val AUTH_OK_WINDOW_MS = 5 * 60_000L
    @Volatile private var pendingReports = 0
    // While a manual login is in progress, the ALREADY-logged-in bots must "chill" —
    // stop the sweep AND validation — so their API traffic doesn't compete with the new
    // login for VRChat's per-IP rate limit (the "3rd/4th bot won't log in" wall).
    private val activeLogins = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var chillDeadline = 0L

    fun beginLogin() {
        activeLogins.incrementAndGet()
        chillDeadline = System.currentTimeMillis() + 6 * 60_000  // safety cap if endLogin is missed
        AvatarCatalogSweep.stop()   // silence the working bots immediately
    }

    fun endLogin() { if (activeLogins.decrementAndGet() < 0) activeLogins.set(0) }

    /** True while any login is running (and within the safety window) — everything else chills. */
    fun chilling(): Boolean = activeLogins.get() > 0 && System.currentTimeMillis() < chillDeadline

    /** Total VRChat silence: a manual pause OR a login in progress. Nothing touches the
     *  VRChat API — no sweep, no validation, no auto-relogin — so the stored cookies just
     *  sit (valid) and NO re-auth is triggered (which is what caused the rate-limit loop). */
    private fun silenced(app: Context): Boolean =
        chilling() || app.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE)
            .getBoolean("bots_paused", false)

    /** Re-apply the sweep config from saved prefs (key / role assignment / pause). Kept
     *  here (not the UI) so the bots auto-start + resume on APP LAUNCH without opening
     *  the Bots tab, and self-include a bot as soon as it's logged in. Idempotent —
     *  ensureRunning only (re)starts when the live set / key / assignment actually change. */
    /** Parse the saved manual role→slot picks (CSV of 4 ints, -1 = auto). */
    private fun currentManual(prefs: android.content.SharedPreferences): Map<AvatarCatalogSweep.Role, Int> {
        val csv = prefs.getString("avatar_role_slots", null)
        val roleSlots = if (csv == null) IntArray(4) { -1 }
            else IntArray(4) { i -> csv.split(",").getOrNull(i)?.toIntOrNull() ?: -1 }
        return AvatarCatalogSweep.Role.values()
            .mapIndexedNotNull { i, r -> roleSlots.getOrNull(i)?.takeIf { it >= 0 }?.let { r to it } }.toMap()
    }

    fun applySweepConfig(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("vrca_admin_local", Context.MODE_PRIVATE)
        // Keep the preview assignment fresh so the per-bot queued rows show what each bot
        // WOULD process even before Start / while paused (independent of ensureRunning).
        AvatarCatalogSweep.setAssignmentPreview(app, currentManual(prefs))
        // avtrdb digestion crawl: driven by the saved toggle, but forced OFF while paused or
        // a login is in progress (it uses a bot session, so it must chill with the rest).
        AvatarCatalogSweep.setAvtrdbCrawl(app,
            prefs.getBoolean("avtrdb_crawl_enabled", false) && !silenced(app))
        // Paused (manual) or chilling (a login is in progress) → the working bots go silent.
        if (silenced(app)) { AvatarCatalogSweep.stop(); return }
        val key = prefs.getString("avatar_admin_key", "") ?: ""
        AvatarCatalogSweep.ensureRunning(app, key, currentManual(prefs))
    }

    /** Idempotent — safe to call on every Bots-tab entry AND on admin app launch. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) return

        // Load + keep the avatar catalog fresh ALWAYS — even when the bots are stopped/paused —
        // so the admin can see the REAL backlog counts and decide whether to run them. (Before,
        // the catalog only loaded while the sweep ran, so the queues sat at 0 until you started
        // the bots — you couldn't tell if there was work.) AvatarGlobalDb.start is idempotent
        // (disk cache + 30-min CDN refresh); the loop pulls the freshest count from the Worker
        // the moment it flushes. All Worker reads, no VRChat — safe during a login/pause.
        AvatarGlobalDb.start(app)
        scope.launch {
            var seenSignal = ""
            while (true) {
                try {
                    // Key on a CONTENT signal (entries:totalAdded:totalRemoved), NOT the
                    // 2-min flush timestamp — so the admin device re-pulls the full file
                    // ONLY when avatars were actually added/removed. A newly-added avatar
                    // reaches the FILL bot within one poll (~30s) of the flush that merged
                    // it, and an idle catalog costs a cheap /health read instead of a 13MB
                    // re-parse every 2 min.
                    val sig = AvatarGlobalDb.workerContentSignal()
                    if (sig != null && sig != seenSignal) {
                        seenSignal = sig
                        AvatarGlobalDb.forceRefresh(app, cacheBust = sig)   // no-op post-cutover (memory-flat)
                    }
                    // Post-cutover: read the tiny manifest for the Bots-tab unfilled backlog —
                    // no whole-catalog scan (the bots walk shards; the count comes from the Action).
                    if (AvatarGlobalDb.shardWalkLive()) {
                        runCatching { AvatarGlobalDb.fetchManifest(app)?.optInt("unfilled", -1) }
                            .getOrNull()?.let { if (it >= 0) AvatarCatalogSweep.setManifestUnfilled(it) }
                    }
                } catch (_: Throwable) { /* transient — retry next tick */ }
                delay(30_000)
            }
        }

        // Loop 1: cheap local state (loggedIn/name from prefs) + backlog/progress views
        // computed off the main thread. No network here, so it's smooth at 2s.
        scope.launch {
            var i = 0
            while (true) {
                // Keep the sweep running per the saved config (auto-starts on launch,
                // self-includes a bot once it's logged in). Idempotent.
                applySweepConfig(app)
                if (i % 6 == 0) pendingReports =
                    runCatching { AvatarGlobalDb.pendingReportCount() }.getOrDefault(pendingReports)
                val vs = withContext(Dispatchers.Default) { AvatarCatalogSweep.roleViews(pendingReports) }
                val bv = withContext(Dispatchers.Default) { AvatarCatalogSweep.blitzViews() }
                _views.value = vs
                // The per-bot queued numbers are SPLIT shares; use the true total backlog for
                // the "To process" pill so splitting the display doesn't distort the grand total.
                _totalQueued.value = AvatarCatalogSweep.lastTotalBacklog
                _blitz.value = AvatarCatalogSweep.blitzActive()
                _blitzViews.value = bv
                _sweepAlive.value = AvatarCatalogSweep.sweepAlive()
                _sweepLastCycleAgoMs.value =
                    if (AvatarCatalogSweep.lastCycleMs == 0L) -1L
                    else System.currentTimeMillis() - AvatarCatalogSweep.lastCycleMs
                _bots.value = _bots.value.map { b ->
                    val li = BotVrchatSession.isLoggedIn(app, b.slot)
                    // Flip "Checking…" (UNKNOWN) to "Authed" the moment the bot proves auth via a
                    // real successful API call — so a bot doing work never sits on "Checking"
                    // waiting for the slow periodic validate (which can stall UNKNOWN on a 429).
                    val provenAuthed = li && b.auth != BotVrchatSession.Auth.AUTHED &&
                        System.currentTimeMillis() - BotVrchatSession.lastAuthOkMs(b.slot) < AUTH_OK_WINDOW_MS
                    b.copy(
                        loggedIn = li,
                        name = BotVrchatSession.accountLabel(app, b.slot),
                        auth = when {
                            !li -> BotVrchatSession.Auth.UNKNOWN
                            provenAuthed -> BotVrchatSession.Auth.AUTHED
                            else -> b.auth
                        }
                    )
                }
                i++; delay(2000)
            }
        }

        // Loop 2: validate each slot. Validating a STORED COOKIE is a plain GET /auth/user
        // — NOT the rate-limited endpoint (only the Basic-auth password login is), so this
        // is cheap and can be quick: the FIRST pass on launch validates all bots within
        // ~10s (so a reopen shows them authed + working fast), then re-checks every ~3 min.
        // autoRelogin (Basic auth, rate-limited) only fires when a cookie has actually
        // EXPIRED. Suspended during a manual login so it doesn't compete for the budget.
        scope.launch {
            var first = true
            while (true) {
                // Gate ONLY on an in-progress manual login (chilling), NOT on the pause.
                // Validating a stored cookie is a plain GET /auth/user — not the rate-limited
                // endpoint — so it's safe while PAUSED, and it's exactly what lets a paused bot
                // show "Authed" (so you can log every bot in, confirm they're all authed, THEN
                // resume). Before, a paused app left logged-in bots stuck on "Checking…".
                if (chilling()) { delay(3000); continue }
                for (slot in 0 until BotVrchatSession.SLOTS) {
                    if (chilling()) break
                    if (BotVrchatSession.isLoggedIn(app, slot)) {
                        var a = BotVrchatSession.validate(app, slot)
                        if (a == BotVrchatSession.Auth.EXPIRED) a = BotVrchatSession.autoRelogin(app, slot)
                        setAuth(slot, a)
                        delay(if (first) 2500 else 15_000)
                    }
                }
                first = false
                delay(3 * 60_000)
            }
        }
    }

    private fun setAuth(slot: Int, a: BotVrchatSession.Auth) {
        _bots.value = _bots.value.map {
            if (it.slot == slot) {
                // A transient UNKNOWN (network / 429 on the periodic validate) must NOT downgrade
                // a bot already known AUTHED — that's the "flips back to Checking" flicker. Only a
                // real EXPIRED (401) or a confirmed AUTHED changes a known-good state.
                val next = if (a == BotVrchatSession.Auth.UNKNOWN && it.auth == BotVrchatSession.Auth.AUTHED)
                    BotVrchatSession.Auth.AUTHED else a
                it.copy(auth = next)
            } else it
        }
    }

    /** Refresh one slot immediately after a login/logout action in the UI. */
    fun refreshSlot(context: Context, slot: Int) {
        val app = context.applicationContext
        scope.launch {
            val li = BotVrchatSession.isLoggedIn(app, slot)
            var a = if (!li) BotVrchatSession.Auth.UNKNOWN else BotVrchatSession.validate(app, slot)
            if (a == BotVrchatSession.Auth.EXPIRED) a = BotVrchatSession.autoRelogin(app, slot)
            _bots.value = _bots.value.map {
                if (it.slot == slot) BotUi(slot, li, BotVrchatSession.accountLabel(app, slot), a) else it
            }
        }
    }
}
