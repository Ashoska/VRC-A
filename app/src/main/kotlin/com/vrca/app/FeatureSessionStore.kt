package com.vrca.app

import android.content.Context

/**
 * Persists the user's active feature toggles (Pinned/AFK, Cycle, Now Playing, Time)
 * to disk so they can be **auto-restored after an unexpected process death** (OS
 * memory pressure, deep Doze, OEM killer) — making such a kill invisible: when the
 * process comes back (watchdog, reboot, or the user reopening) the senders resume
 * exactly as they were.
 *
 * A **deliberate swipe** is different: [AppShutdown.onTaskSwiped] calls [disarm],
 * which clears the restore flag so the next launch starts clean. This preserves the
 * documented "toggles start OFF on a fresh, intentional launch" behavior while making
 * the app behave as if it was never killed when the OS kills it on its own.
 *
 * Backed by the same `vrca_remote` prefs file AppShutdown uses, so the swipe-time
 * disarm is part of the same synchronous commit.
 */
object FeatureSessionStore {
    private const val KEY_ARMED = "feature_session_armed"
    private const val KEY_AFK = "feature_afk_enabled"
    private const val KEY_CYCLE = "feature_cycle_enabled"
    private const val KEY_SPOTIFY = "feature_spotify_enabled"
    private const val KEY_TIME = "feature_time_enabled"
    private const val KEY_SENDING = "feature_osc_sending"
    private const val KEY_SENDING_SINCE = "feature_sending_since"
    private const val KEY_SENDING_SEEN = "feature_sending_seen"

    data class State(
        val afk: Boolean,
        val cycle: Boolean,
        val spotify: Boolean,
        val time: Boolean,
        /** Whether OSC was actively transmitting (START pressed) at persist time.
         *  Restore only RESUMES sending when this is true — having toggles
         *  configured but never started must not auto-start the chatbox. */
        val sending: Boolean,
        /** Epoch ms of when the current sending session started (the Home uptime
         *  counter). 0 when not sending. */
        val sendingSinceMs: Long = 0L,
        /** Last heartbeat while sending — the grace-window key for the uptime
         *  restore (same pattern as the Discord RPC online counter). */
        val lastSendingSeenMs: Long = 0L
    ) {
        val anyEnabled: Boolean get() = afk || cycle || spotify || time
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(AppShutdown.PREFS, Context.MODE_PRIVATE)

    /** Persist the current toggle state + send gate and arm restore for the next launch. */
    fun save(
        context: Context,
        afk: Boolean,
        cycle: Boolean,
        spotify: Boolean,
        time: Boolean,
        sending: Boolean,
        sendingSinceMs: Long = 0L
    ) {
        try {
            val editor = prefs(context).edit()
                .putBoolean(KEY_ARMED, true)
                .putBoolean(KEY_AFK, afk)
                .putBoolean(KEY_CYCLE, cycle)
                .putBoolean(KEY_SPOTIFY, spotify)
                .putBoolean(KEY_TIME, time)
                .putBoolean(KEY_SENDING, sending)
                .putLong(KEY_SENDING_SINCE, if (sending) sendingSinceMs else 0L)
            // Every persist while sending also refreshes the heartbeat — the
            // restore grace window keys on how recently sending was last alive.
            if (sending) editor.putLong(KEY_SENDING_SEEN, System.currentTimeMillis())
            editor.apply()
        } catch (_: Throwable) {}
    }

    /** Refresh the sending heartbeat without touching the toggle set. Called on
     *  a slow cadence (~60s) while OSC is transmitting so an OS-kill revival can
     *  tell a short watchdog gap (continue the uptime counter) from a long dead
     *  window (start the counter fresh). */
    fun heartbeatSending(context: Context) {
        try {
            prefs(context).edit().putLong(KEY_SENDING_SEEN, System.currentTimeMillis()).apply()
        } catch (_: Throwable) {}
    }

    /** Returns the toggles to auto-restore, or null if restore is not armed
     *  (fresh install or the last session ended via a deliberate swipe). */
    fun pendingRestore(context: Context): State? {
        return try {
            val p = prefs(context)
            if (!p.getBoolean(KEY_ARMED, false)) return null
            State(
                afk = p.getBoolean(KEY_AFK, false),
                cycle = p.getBoolean(KEY_CYCLE, false),
                spotify = p.getBoolean(KEY_SPOTIFY, false),
                time = p.getBoolean(KEY_TIME, false),
                sending = p.getBoolean(KEY_SENDING, false),
                sendingSinceMs = p.getLong(KEY_SENDING_SINCE, 0L),
                lastSendingSeenMs = p.getLong(KEY_SENDING_SEEN, 0L)
            )
        } catch (_: Throwable) {
            null
        }
    }

    /** Disarm restore. Called on a deliberate swipe so the next launch starts clean. */
    fun disarm(context: Context) {
        try {
            prefs(context).edit().putBoolean(KEY_ARMED, false).apply()
        } catch (_: Throwable) {}
    }
}
