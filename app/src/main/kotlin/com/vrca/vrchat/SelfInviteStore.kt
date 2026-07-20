package com.vrca.vrchat

import android.content.Context
import org.json.JSONArray

/**
 * Persisted state for the admin self-invite flow (see [SelfInviteCoordinator]).
 *
 * Two jobs, both on-device (SharedPreferences `vrca_self_invite`, no Firestore):
 *
 *  1. **Friend-notification exemption.** The self-invite fallback dance briefly
 *     friends + unfriends the counterpart (the admin, on the user's phone). The user
 *     must NOT see any of it. [isFriendNotifSuppressed] returns true for the global
 *     owner VRChat id ([ownerVrchatId] — set from the config/app doc AND from the
 *     self-invite signal's adminId) OR any id currently in the [pendingUnfriend] set
 *     (an active/persisted dance counterpart). The pipeline's friend-add / friend-
 *     delete / friend-request handlers consult this and skip firing. ONLY friend-list
 *     notifications are gated — bio/name/rank/online/etc. are untouched.
 *
 *  2. **Unfriend cleanup backup.** A counterpart we still owe an unfriend is stored in
 *     [pendingUnfriend] (added BEFORE friending, so a mid-dance crash/connection dip
 *     still cleans up). The friends-profile sweep drains it — unfriend, then remove —
 *     so the admin is guaranteed unfriended even if the 10s in-dance force-unfriend
 *     never landed, and the counterpart is freed for a future dance. Entries are only
 *     ever added when the counterpart was NOT already a friend, so cleanup can never
 *     unfriend a genuine friend.
 */
object SelfInviteStore {
    private const val PREFS = "vrca_self_invite"
    private const val KEY_OWNER_VRC_ID = "owner_vrchat_id"
    private const val KEY_PENDING_UNFRIEND = "pending_unfriend"
    private const val KEY_LAST_HANDLED_SIGNAL_MS = "last_handled_signal_ms"

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- owner id (global friend-notif exemption) ---

    fun ownerVrchatId(ctx: Context): String =
        prefs(ctx).getString(KEY_OWNER_VRC_ID, "").orEmpty()

    /** Set the global owner VRChat id. Only overwrites with a non-blank `usr_` id. */
    fun setOwnerVrchatId(ctx: Context, id: String) {
        val v = id.trim()
        if (v.isBlank() || !v.startsWith("usr_")) return
        if (v == ownerVrchatId(ctx)) return
        prefs(ctx).edit().putString(KEY_OWNER_VRC_ID, v).apply()
    }

    // --- pending-unfriend cleanup backup ---

    fun pendingUnfriend(ctx: Context): Set<String> {
        val raw = prefs(ctx).getString(KEY_PENDING_UNFRIEND, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildSet { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
        } catch (_: Exception) { emptySet() }
    }

    fun addPendingUnfriend(ctx: Context, userId: String) {
        val id = userId.trim()
        if (id.isBlank()) return
        val cur = pendingUnfriend(ctx)
        if (id in cur) return
        writePending(ctx, cur + id)
    }

    fun removePendingUnfriend(ctx: Context, userId: String) {
        val id = userId.trim()
        val cur = pendingUnfriend(ctx)
        if (id !in cur) return
        writePending(ctx, cur - id)
    }

    private fun writePending(ctx: Context, set: Set<String>) {
        val arr = JSONArray()
        set.forEach { arr.put(it) }
        // commit() — a swipe/kill can race an async apply(), and losing this write
        // means a friend we owe an unfriend to lingers on the account.
        prefs(ctx).edit().putString(KEY_PENDING_UNFRIEND, arr.toString()).commit()
    }

    // --- friend-notification suppression check ---

    fun isFriendNotifSuppressed(ctx: Context, userId: String): Boolean {
        val id = userId.trim()
        if (id.isBlank()) return false
        return id == ownerVrchatId(ctx) || id in pendingUnfriend(ctx)
    }

    // --- signal dedup (user side) ---

    fun lastHandledSignalMs(ctx: Context): Long =
        prefs(ctx).getLong(KEY_LAST_HANDLED_SIGNAL_MS, 0L)

    fun setLastHandledSignalMs(ctx: Context, ms: Long) {
        prefs(ctx).edit().putLong(KEY_LAST_HANDLED_SIGNAL_MS, ms).commit()
    }
}
