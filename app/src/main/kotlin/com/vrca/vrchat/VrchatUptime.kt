package com.vrca.vrchat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "How long you've been in VRChat" timer — the value the Home uptime label shows,
 * so it reflects VRChat presence time instead of how long the chatbox has been
 * sending. Deliberately mirrors the Discord RPC online counter: driven by
 * [VrcUserPresence.isOnlineInVRChat], with a 20-minute GRACE window so a brief
 * presence drop / process kill doesn't reset it, and crash-time saving (the start
 * epoch + a last-seen heartbeat are persisted), so it survives an OS kill exactly
 * like the RPC timer — and works whether or not the Discord RPC is enabled.
 *
 * `startFlow` holds the online-start epoch (ms), or 0 when not in VRChat; the UI
 * shows `now - start`.
 */
object VrchatUptime {

    private const val PREFS = "vrca_vrchat_uptime"
    private const val KEY_START = "online_start"
    private const val KEY_SEEN = "last_seen"
    private const val GRACE_MS = 20L * 60 * 1000 // matches DiscordRpc ONLINE_GRACE_MS

    private val _startFlow = MutableStateFlow(0L)
    /** Online-start epoch in ms, or 0 when not currently in VRChat. */
    val startFlow: StateFlow<Long> = _startFlow.asStateFlow()

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Restore on a fresh process: resume the timer only if the last-seen heartbeat
     *  is within the grace window (a short kill/drop); otherwise start blank. The
     *  presence collector's next [update] corrects it either way. */
    fun attach(ctx: Context) {
        val p = prefs(ctx)
        val start = p.getLong(KEY_START, 0L)
        val seen = p.getLong(KEY_SEEN, 0L)
        val now = System.currentTimeMillis()
        _startFlow.value = if (start > 0L && seen > 0L && now - seen <= GRACE_MS) start else 0L
    }

    /** Feed the live presence state. `now` is System.currentTimeMillis(). */
    fun update(ctx: Context, inVrchat: Boolean, now: Long) {
        val p = prefs(ctx)
        if (inVrchat) {
            var start = _startFlow.value
            if (start <= 0L) {
                // Coming online: resume within grace, else start fresh.
                val prevStart = p.getLong(KEY_START, 0L)
                val prevSeen = p.getLong(KEY_SEEN, 0L)
                start = if (prevStart > 0L && prevSeen > 0L && now - prevSeen <= GRACE_MS) prevStart else now
                _startFlow.value = start
                p.edit().putLong(KEY_START, start).putLong(KEY_SEEN, now).apply()
            } else {
                // Still online: refresh the heartbeat so a later kill can resume.
                p.edit().putLong(KEY_SEEN, now).apply()
            }
        } else {
            // Not in VRChat: hide the timer. We DON'T rewrite last_seen, so the
            // grace window still bridges a brief drop (next online resumes); a
            // genuinely long offline lets the next online start fresh.
            if (_startFlow.value != 0L) _startFlow.value = 0L
        }
    }
}
