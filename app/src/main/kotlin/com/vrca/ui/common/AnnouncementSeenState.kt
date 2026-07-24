package com.vrca.ui.common

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Process-scoped observable set of "seen" announcement ids (Phase 4).
 *
 * Announcements are NOT dismissable, so this is how the list stays fresh: an
 * announcement is unread until its card is first expanded. The per-card NEW dot and
 * the Home-nav count badge both read [seen]. Persisted to SharedPreferences so it
 * survives restarts. Mirrors the StatusBannerState / AlertSectionState singleton
 * pattern. Uses a `Set` behind `mutableStateOf` (not `mutableStateSetOf`, which
 * isn't in this project's Compose runtime version).
 */
object AnnouncementSeenState {
    private const val FILE = "vrca_ann_seen"
    private const val KEY = "ids"
    private var loaded = false

    var seen by mutableStateOf<Set<String>>(emptySet())
        private set

    fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            seen = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
        }
    }

    fun markSeen(ctx: Context, id: String) {
        if (id.isBlank() || id in seen) return
        seen = seen + id
        runCatching {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putStringSet(KEY, seen).apply()
        }
    }

    /** Count of active announcement ids the user hasn't opened yet. */
    fun unseenCount(activeIds: List<String>): Int = activeIds.count { it !in seen }
}
