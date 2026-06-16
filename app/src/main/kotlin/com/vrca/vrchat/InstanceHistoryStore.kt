package com.vrca.vrchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records the instances the user has personally been in over the last 24 hours so
 * they can re-invite themselves to any of them from the VRChat tab, and cross-
 * reference what they were playing at what time. Each entry carries the FULL joinable
 * location (including the `~nonce(...)` access token captured from the self
 * `user-location` pipeline event), the world name, and the times the user JOINED and
 * LEFT it ([joinedMs] / [leftMs]; leftMs == 0 means they're still in it).
 *
 * The nonce is the access grant for invite-only/friends instances; it lives only in
 * this device's local prefs (never synced to Firestore), exactly like the user's own
 * cookie — it is their own access to a place they were already in.
 *
 * Entries left more than [RETENTION_MS] (24h) ago are pruned on every read/write; an
 * instance the user is still in is never pruned.
 */
object InstanceHistoryStore {
    private const val PREFS = "vrca_instance_history"
    private const val KEY = "entries_json"
    private const val RETENTION_MS = 24L * 60 * 60 * 1000
    private const val MAX_ENTRIES = 50

    data class Entry(
        val location: String,
        val worldName: String,
        val joinedMs: Long,
        val leftMs: Long // 0 == still in this instance (current)
    )

    /**
     * Records that the user just ENTERED [location]. Marks whatever instance they were
     * previously in as left (now), and either starts a fresh visit for this instance
     * (new join time) or marks an existing one current again. Only full `wrld_...`
     * locations are stored.
     */
    fun record(ctx: Context, location: String?, worldName: String?) {
        val loc = location?.trim().orEmpty()
        if (loc.isBlank() || !loc.startsWith("wrld_") ||
            loc == "offline" || loc == "private" || loc == "traveling"
        ) return
        val now = System.currentTimeMillis()
        val entries = read(ctx).toMutableList()
        // We've moved here, so any other instance still marked "current" was left now.
        for (i in entries.indices) {
            if (entries[i].leftMs == 0L && entries[i].location != loc) {
                entries[i] = entries[i].copy(leftMs = now)
            }
        }
        val idx = entries.indexOfFirst { it.location == loc }
        if (idx >= 0) {
            val e = entries.removeAt(idx)
            // If we had already left this instance, re-entering is a NEW visit (fresh
            // join time); if it was still current this is just a redundant event.
            val newJoined = if (e.leftMs != 0L) now else e.joinedMs
            entries.add(
                e.copy(
                    joinedMs = newJoined,
                    leftMs = 0L,
                    worldName = worldName?.takeIf { it.isNotBlank() } ?: e.worldName
                )
            )
        } else {
            entries.add(Entry(loc, worldName.orEmpty(), now, 0L))
        }
        persist(ctx, entries)
    }

    /**
     * Marks the instance the user is currently in (if any) as LEFT now — called when
     * they go offline / leave VRChat without hopping to another instance.
     */
    fun markCurrentLeft(ctx: Context) {
        val now = System.currentTimeMillis()
        val entries = read(ctx).toMutableList()
        var changed = false
        for (i in entries.indices) {
            if (entries[i].leftMs == 0L) {
                entries[i] = entries[i].copy(leftMs = now)
                changed = true
            }
        }
        if (changed) persist(ctx, entries)
    }

    /** Past-24h instances, current first then most-recently-left, pruned and capped. */
    fun list(ctx: Context): List<Entry> = read(ctx).sortedByDescending {
        if (it.leftMs == 0L) Long.MAX_VALUE else it.leftMs
    }

    private fun read(ctx: Context): List<Entry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val arr = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val left = o.optLong("left")
            // Prune entries left more than 24h ago; keep anything still current.
            if (left != 0L && left < cutoff) continue
            out += Entry(
                location = o.optString("loc"),
                worldName = o.optString("world"),
                joinedMs = o.optLong("joined"),
                leftMs = left
            )
        }
        return out
    }

    private fun persist(ctx: Context, entries: List<Entry>) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val kept = entries
            .filter { it.leftMs == 0L || it.leftMs >= cutoff }
            .sortedByDescending { if (it.leftMs == 0L) Long.MAX_VALUE else it.leftMs }
            .take(MAX_ENTRIES)
        val arr = JSONArray()
        for (e in kept) {
            arr.put(JSONObject().apply {
                put("loc", e.location)
                put("world", e.worldName)
                put("joined", e.joinedMs)
                put("left", e.leftMs)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
