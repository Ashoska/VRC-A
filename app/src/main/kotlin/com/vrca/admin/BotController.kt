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

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pendingReports = 0

    /** Idempotent — safe to call on every Bots-tab entry. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) return

        // Loop 1: cheap local state (loggedIn/name from prefs) + backlog/progress views
        // computed off the main thread. No network here, so it's smooth at 2s.
        scope.launch {
            var i = 0
            while (true) {
                if (i % 6 == 0) pendingReports =
                    runCatching { AvatarGlobalDb.pendingReportCount() }.getOrDefault(pendingReports)
                val vs = withContext(Dispatchers.Default) { AvatarCatalogSweep.roleViews(pendingReports) }
                val bv = withContext(Dispatchers.Default) { AvatarCatalogSweep.blitzViews() }
                _views.value = vs
                _totalQueued.value = vs.sumOf { it.queued }
                _blitz.value = AvatarCatalogSweep.blitzActive()
                _blitzViews.value = bv
                _bots.value = _bots.value.map { b ->
                    val li = BotVrchatSession.isLoggedIn(app, b.slot)
                    b.copy(
                        loggedIn = li,
                        name = BotVrchatSession.accountLabel(app, b.slot),
                        auth = if (!li) BotVrchatSession.Auth.UNKNOWN else b.auth
                    )
                }
                i++; delay(2000)
            }
        }

        // Loop 2: validate + auto-relogin each slot, ONE AT A TIME, staggered — so four
        // slots never hit /auth/user at once (VRChat rate-limits that, which was the
        // "de-auths a lot" cause). Each slot is re-checked ~every 90s.
        scope.launch {
            while (true) {
                for (slot in 0 until BotVrchatSession.SLOTS) {
                    if (BotVrchatSession.isLoggedIn(app, slot)) {
                        var a = BotVrchatSession.validate(app, slot)
                        if (a == BotVrchatSession.Auth.EXPIRED) a = BotVrchatSession.autoRelogin(app, slot)
                        setAuth(slot, a)
                    }
                    delay(7000)
                }
                delay(45_000)
            }
        }
    }

    private fun setAuth(slot: Int, a: BotVrchatSession.Auth) {
        _bots.value = _bots.value.map { if (it.slot == slot) it.copy(auth = a) else it }
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
