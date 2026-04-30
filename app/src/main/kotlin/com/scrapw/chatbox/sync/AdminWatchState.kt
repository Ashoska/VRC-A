package com.scrapw.chatbox.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks whether an admin is currently viewing this user's detail page.
 *
 * Admins refresh `watcherActiveAt` on the user's Firestore doc every ~30s
 * while a user is selected in the admin panel. The user app reads that
 * timestamp from its own snapshot and feeds it into [updateFromTimestampMs];
 * if it's within the freshness window, [isWatched] flips to true and the
 * "live" sync loops (VRChat presence, NowPlaying preview, etc.) start
 * running. When the timestamp goes stale (admin closed the panel or
 * crashed), [isWatched] flips back to false and live sync stops.
 *
 * No periodic Firestore traffic happens when `isWatched == false`. This
 * is the core mechanism that keeps cost minimal while still letting
 * admins see live data for whichever user they're focused on.
 */
object AdminWatchState {
    /** Window after the most recent watcher heartbeat that counts as "still watching". */
    const val FRESHNESS_WINDOW_MS = 60_000L

    private val _isWatched = MutableStateFlow(false)
    val isWatched: StateFlow<Boolean> = _isWatched

    /**
     * Update the watched flag from a `watcherActiveAt` timestamp (ms since epoch).
     * Pass null/0 if the field is absent — that counts as not watched.
     */
    fun updateFromTimestampMs(activeAtMs: Long?) {
        val fresh = activeAtMs != null &&
            activeAtMs > 0 &&
            System.currentTimeMillis() - activeAtMs < FRESHNESS_WINDOW_MS
        if (_isWatched.value != fresh) {
            _isWatched.value = fresh
        }
    }
}
