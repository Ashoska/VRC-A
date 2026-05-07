package com.scrapw.chatbox.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks whether ANY admin is currently browsing the dashboard or user
 * directory in the admin panel.
 *
 * Admins write `config/adminPresence.browsingAt = serverTimestamp()` every
 * 30s while on the Dashboard or Users tab. Public user apps subscribe to
 * that single doc. When fresh (within FRESHNESS_WINDOW_MS), [isBrowsing]
 * flips true and a 30s heartbeat in VrchatPipelineService starts writing
 * `lastSeenAt` so admins can detect force-killed users via staleness.
 *
 * When the admin closes both tabs (or the admin app), the doc goes stale
 * and heartbeats stop. Zero Firestore traffic when nobody is looking.
 */
object AdminBrowsingState {
    const val FRESHNESS_WINDOW_MS = 75_000L

    private val _isBrowsing = MutableStateFlow(false)
    val isBrowsing: StateFlow<Boolean> = _isBrowsing

    fun updateFromTimestampMs(browsingAtMs: Long?) {
        val fresh = browsingAtMs != null &&
            browsingAtMs > 0 &&
            System.currentTimeMillis() - browsingAtMs < FRESHNESS_WINDOW_MS
        if (_isBrowsing.value != fresh) {
            _isBrowsing.value = fresh
        }
    }
}
