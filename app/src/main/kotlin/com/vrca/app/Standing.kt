package com.vrca.app

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import com.vrca.ui.theme.GremlinColors
import kotlinx.coroutines.launch

/**
 * Account standing (revamp 2, 2026-10-31; docs/systems/moderation-accounts.md "Account standing").
 *
 * The admin writes the user's view of their standing to their own `users/{deviceHash}` doc as one map field,
 * `standing` (account-wide, admin-only key in firestore.rules), so the app reads it from the moderation listener it
 * already has: no extra reads, and every admin edit (a hidden evidence file, a changed length) shows live.
 * The full records (admin note, every evidence file and its visibility, history) are in `standingActions/{id}`,
 * which only the admin can read.
 *
 * `standing` = { rev: Long (ms, bumped by every admin edit), level: "good|limited|verylimited|atrisk|banned",
 *   actions: [ { id, kind: "warning|restriction|ban", title, message, given: Long ms, active: Bool,
 *                off: { featureKey: untilMs (-1 = forever) }, banUntil: Long (0 none, -1 forever, else ms),
 *                ev: [ { id, kind } ] (only files shown to the user) } ] }
 *
 * Time: lengths end by the SERVER's clock, never the phone's (a changed phone clock can't end a ban early). Boot
 * reads the server time from the website's /status reply ([anchorServerTime]); [nowMs] counts on from it with the
 * time since power-on, which changing the phone's time doesn't touch. Until that's known it uses the phone's clock.
 * Real enforcement of what goes through our servers is the server's job; this is what honest users see.
 */
object Standing {
    enum class Level(val key: String, val label: String, val line: String, val mark: Mark) {
        Good("good", "All good", "Your account is in good standing. Thanks for following the rules.", Mark.Done),
        Limited("limited", "Limited", "Some features are disabled for a while.", Mark.Warn),
        VeryLimited("verylimited", "Very limited", "Several features are disabled for a while.", Mark.Warn),
        AtRisk("atrisk", "At risk", "One more serious action could get you banned from VRC-A.", Mark.Failed),
        Banned("banned", "Banned", "Your access to VRC-A is suspended.", Mark.Failed);

        val color: Color get() = when (this) {
            Good -> GremlinColors.Success; Limited -> GremlinColors.Warning; VeryLimited -> GremlinColors.Caution
            AtRisk -> GremlinColors.Error; Banned -> GremlinColors.Alert
        }

        companion object { fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Good }
    }

    /** The features an admin can disable, each enforced where it lives (see the doc for where). */
    enum class Feature(val key: String, val label: String) {
        ChatboxAll("chatbox_all", "Chatbox sending"),
        ManualSend("chatbox_manual", "Manual Send"),
        Voice("voice", "Voice to text"),
        AvatarSearch("avatar_search", "Avatar search"),
        AvatarClone("avatar_clone", "Avatar clone"),
        AvatarContribute("avatar_contribute", "Contributing avatars"),
        Discord("discord", "Discord status");

        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } }
    }

    @Immutable data class Evidence(val id: String, val kind: String)

    @Immutable data class Action(
        val id: String, val kind: String, val title: String, val message: String, val given: Long, val active: Boolean,
        /** Feature key → end (ms), -1 = forever. */
        val off: Map<String, Long>, val banUntil: Long, val evidence: List<Evidence>
    )

    @Immutable data class State(val rev: Long, val level: Level, val actions: List<Action>)

    /** The standing as last read (null = none on record). Watched state: everything shown from it updates live. */
    val state = mutableStateOf<State?>(null)
    /** Bumped when a length runs out, so gates and screens re-check (see [startClock]). */
    val tick = mutableLongStateOf(0L)

    // ---- clock --------------------------------------------------------------------------------------------
    @Volatile private var anchorServerMs = 0L
    @Volatile private var anchorElapsedMs = 0L

    /** Server time (ms) as of now, from an HTTP `Date` header or similar. */
    fun anchorServerTime(serverMs: Long) {
        if (serverMs <= 0L) return
        anchorServerMs = serverMs; anchorElapsedMs = SystemClock.elapsedRealtime()
    }

    fun nowMs(): Long = if (anchorServerMs > 0L) anchorServerMs + (SystemClock.elapsedRealtime() - anchorElapsedMs)
        else System.currentTimeMillis()

    // ---- reading ------------------------------------------------------------------------------------------
    /** Parses the `standing` field (a Firestore map) and publishes it. Returns the parsed state. */
    fun publish(raw: Any?): State? {
        val s = parse(raw)
        if (s != state.value) state.value = s
        return s
    }

    @Suppress("UNCHECKED_CAST")
    fun parse(raw: Any?): State? {
        val m = raw as? Map<String, Any?> ?: return null
        val actions = (m["actions"] as? List<Any?>).orEmpty().mapNotNull { a ->
            val o = a as? Map<String, Any?> ?: return@mapNotNull null
            Action(
                id = o["id"] as? String ?: return@mapNotNull null,
                kind = o["kind"] as? String ?: "warning",
                title = o["title"] as? String ?: "",
                message = o["message"] as? String ?: "",
                given = (o["given"] as? Number)?.toLong() ?: 0L,
                active = o["active"] as? Boolean ?: true,
                off = (o["off"] as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> (v as? Number)?.let { k to it.toLong() } }.toMap(),
                banUntil = (o["banUntil"] as? Number)?.toLong() ?: 0L,
                evidence = (o["ev"] as? List<Any?>).orEmpty().mapNotNull { e ->
                    val eo = e as? Map<String, Any?> ?: return@mapNotNull null
                    Evidence(eo["id"] as? String ?: return@mapNotNull null, eo["kind"] as? String ?: "png")
                }
            )
        }.sortedByDescending { it.given }
        return State((m["rev"] as? Number)?.toLong() ?: 0L, Level.of(m["level"] as? String), actions)
    }

    private fun live(until: Long, now: Long) = until == -1L || until > now

    /** When [f] comes back (ms; -1 = never), or null when it's on. */
    fun offUntil(f: Feature, s: State? = state.value): Long? {
        val now = nowMs()
        var best: Long? = null
        s?.actions?.forEach { a ->
            if (!a.active) return@forEach
            val u = a.off[f.key] ?: return@forEach
            if (!live(u, now)) return@forEach
            best = if (u == -1L || best == -1L) -1L else maxOf(best ?: 0L, u)
        }
        return best
    }

    fun isOff(f: Feature): Boolean { tick.longValue; return isBanned() || offUntil(f) != null }

    /** An active ban that hasn't run out. */
    fun banUntil(s: State? = state.value): Long? {
        val now = nowMs()
        var best: Long? = null
        s?.actions?.forEach { a ->
            if (!a.active || a.kind != "ban" || a.banUntil == 0L || !live(a.banUntil, now)) return@forEach
            best = if (a.banUntil == -1L || best == -1L) -1L else maxOf(best ?: 0L, a.banUntil)
        }
        return best
    }

    fun isBanned(): Boolean { tick.longValue; return banUntil() != null }

    /** Every disabled feature with its end, longest first, for the standing card. */
    fun disabled(s: State?): List<Pair<String, Long>> {
        if (s == null) return emptyList()
        banUntil(s)?.let { return listOf("Everything in VRC-A" to it) }
        return Feature.entries.mapNotNull { f -> offUntil(f, s)?.let { f.label to it } }
            .sortedByDescending { if (it.second == -1L) Long.MAX_VALUE else it.second }
    }

    /** "2 days left", "6 h left", "Permanent". */
    fun leftText(until: Long): String {
        if (until == -1L) return "Permanent"
        val ms = (until - nowMs()).coerceAtLeast(0L)
        val h = ms / 3_600_000L
        return when {
            h >= 48 -> "${h / 24} days left"
            h >= 1 -> "$h h left"
            else -> "${(ms / 60_000L).coerceAtLeast(1)} min left"
        }
    }

    /** How much of a length is left (0..1) for the draining Boot Cat; [given] is when it started. */
    fun leftFraction(until: Long, given: Long): Float {
        if (until == -1L) return 1f
        val total = (until - given).coerceAtLeast(1L)
        return ((until - nowMs()).toFloat() / total).coerceIn(0f, 1f)
    }

    /** The start of whichever action set [until] for a feature label (for [leftFraction]). */
    fun givenFor(label: String, s: State?): Long {
        val f = Feature.entries.firstOrNull { it.label == label }
        return s?.actions?.filter { it.active }?.firstOrNull { a ->
            if (f == null) a.kind == "ban" else a.off.containsKey(f.key)
        }?.given ?: 0L
    }

    /**
     * Wakes when the next length runs out (and every minute for the "time left" text), bumping [tick] so everything
     * re-checks; [onChange] lets the caller re-apply its gates (the OSC gate). One loop per process.
     */
    @Volatile private var clockStarted = false
    fun startClock(scope: kotlinx.coroutines.CoroutineScope, onChange: () -> Unit) {
        if (clockStarted) return
        clockStarted = true
        scope.launch {
            while (true) {
                val now = nowMs()
                val next = state.value?.actions.orEmpty().filter { it.active }
                    .flatMap { a -> a.off.values + a.banUntil }.filter { it > now }.minOrNull()
                kotlinx.coroutines.delay(((next ?: Long.MAX_VALUE) - now).coerceIn(1_000L, 60_000L))
                tick.longValue++
                onChange()
            }
        }
    }

    // ---- "new or changed since the user last looked" -------------------------------------------------------
    private const val PREFS = "vrca_standing"
    private const val SEEN_REV = "seen_rev"

    /** The popup opens by itself when something changed since the user last closed it (or while banned). */
    fun shouldPop(ctx: Context, s: State? = state.value): Boolean {
        if (s == null) return false
        if (banUntil(s) != null) return true
        val seen = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SEEN_REV, 0L)
        return s.rev > seen && s.actions.any { it.active }
    }

    fun markSeen(ctx: Context, s: State? = state.value) {
        val rev = s?.rev ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(SEEN_REV, rev).apply()
    }

    /** The boot row's word for this standing (and whether it's new/changed). */
    fun bootNote(ctx: Context, s: State?): BootNote = when {
        s == null -> BootNote.Good
        banUntil(s) != null -> BootNote.Banned
        shouldPop(ctx, s) -> if (s.actions.firstOrNull { it.active }?.kind == "warning") BootNote.NewWarning else BootNote.ActionUpdated
        else -> when (s.level) {
            Level.Good -> BootNote.Good; Level.Limited -> BootNote.Limited; Level.VeryLimited -> BootNote.VeryLimited
            Level.AtRisk -> BootNote.AtRisk; Level.Banned -> BootNote.Good
        }
    }
}
