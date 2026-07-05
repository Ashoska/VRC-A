package com.vrca.vrchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Records the instances the user has personally been in over the last 24 hours so
 * they can re-invite themselves to any of them from the VRChat tab, and cross-
 * reference what they were playing at what time.
 *
 * **One [Entry] per instance** (keyed by the FULL joinable [location] string, which
 * includes the `~nonce(...)` access token captured from the self `user-location`
 * pipeline event). Each entry holds a list of [Session]s — every distinct VISIT to
 * that instance — so rejoining a place you'd already left no longer DESTROYS the
 * earlier visit's times; both are kept and shown stacked in the picker.
 *
 * The nonce is the access grant for invite-only/friends instances; it lives only in
 * this device's local prefs (never synced to Firestore), exactly like the user's own
 * cookie — it is their own access to a place they were already in. A different nonce
 * (a fresh invite to the "same" world) is a different location string, so it is a
 * different entry — correct, since either nonce might work for a re-invite.
 *
 * Sessions older than [RETENTION_MS] (24h) are pruned on every read/write; an entry
 * with any still-current session (leftMs == 0) is never pruned. There is deliberately
 * NO entry cap — the 24h window is the natural bound (worst case a few dozen entries,
 * well under 100 KB).
 */
object InstanceHistoryStore {
    private const val PREFS = "vrca_instance_history"
    private const val KEY = "entries_json"
    private const val RETENTION_MS = 24L * 60 * 60 * 1000

    /**
     * Short-gap merge window. When the user re-enters an instance they left less than
     * this ago — AND nothing else was joined in between (see [record]) — the previous
     * session is REOPENED instead of starting a new one. This collapses headset-off /
     * pipeline-reconnect blips into one continuous visit. 10 minutes is wide enough to
     * absorb a headset coming off + reconnect backoff; a genuine "left for longer and
     * came back" registers as a separate session.
     *
     * Tradeoff (intended): stepping away from the SAME instance for under 10 min with
     * nothing in between shows as one continuous session, not two — exactly the
     * headset-off behavior we want. Do not "fix" this into a strict split.
     */
    private const val MERGE_GAP_MS = 10L * 60 * 1000

    data class Session(
        val joinedMs: Long,
        val leftMs: Long // 0 == still in this instance (current)
    )

    data class Entry(
        val location: String,
        val worldName: String,
        val sessions: List<Session>, // chronological; newest last
        // World thumbnail URL, learned the first time instance info resolves in the
        // picker. The bytes persist in AlertImageStore until this entry ages out of
        // the 24h window (the store's gc sees the reference vanish) — so reopening
        // the history dialog shows images instantly instead of re-downloading.
        val imageUrl: String = ""
    )

    /**
     * Records that the user just ENTERED [location]. Marks whatever OTHER instance they
     * were in as left (now), then either reopens this instance's last session (a
     * reconnect within [MERGE_GAP_MS] with no activity elsewhere in between) or starts a
     * fresh session. Only full `wrld_...` locations are stored.
     */
    fun record(ctx: Context, location: String?, worldName: String?) {
        val loc = location?.trim().orEmpty()
        if (loc.isBlank() || !loc.startsWith("wrld_") ||
            loc == "offline" || loc == "private" || loc == "traveling"
        ) return
        val now = System.currentTimeMillis()
        val entries = read(ctx).toMutableList()

        // We've moved here, so any OTHER instance still marked current was left now.
        for (i in entries.indices) {
            val e = entries[i]
            if (e.location == loc) continue
            val last = e.sessions.lastOrNull()
            if (last != null && last.leftMs == 0L) {
                val s = e.sessions.toMutableList()
                s[s.size - 1] = last.copy(leftMs = now)
                entries[i] = e.copy(sessions = s)
            }
        }

        val idx = entries.indexOfFirst { it.location == loc }
        if (idx < 0) {
            entries.add(Entry(loc, worldName.orEmpty(), listOf(Session(now, 0L))))
            persist(ctx, entries)
            return
        }

        val e = entries[idx]
        val newWorld = worldName?.takeIf { it.isNotBlank() } ?: e.worldName
        val sessions = e.sessions.toMutableList()
        val last = sessions.lastOrNull()

        if (last != null && last.leftMs == 0L) {
            // Already current here (redundant event) — just refresh the world name.
            entries[idx] = e.copy(worldName = newWorld)
        } else {
            // The most recent activity across every OTHER entry. If anything happened
            // elsewhere AFTER we left this instance, this is a genuine hop-and-return,
            // NOT a reconnect, so we must start a new session (merging would smear the
            // timeline over the time we were demonstrably elsewhere).
            val maxOtherActivity = entries
                .filterIndexed { i, _ -> i != idx }
                .flatMap { it.sessions }
                .maxOfOrNull { if (it.leftMs == 0L) now else it.leftMs } ?: 0L

            val canMerge = last != null &&
                last.leftMs != 0L &&
                (now - last.leftMs) <= MERGE_GAP_MS &&
                last.leftMs >= maxOtherActivity

            if (canMerge) {
                sessions[sessions.size - 1] = last!!.copy(leftMs = 0L) // reopen
            } else {
                sessions.add(Session(now, 0L))
            }
            entries[idx] = e.copy(sessions = sessions, worldName = newWorld)
        }
        persist(ctx, entries)
    }

    /**
     * Marks the instance the user is currently in (if any) as LEFT now — called when
     * they go offline / leave VRChat without hopping to another instance. Closes the
     * current session of every entry that still has one open.
     */
    fun markCurrentLeft(ctx: Context) {
        val now = System.currentTimeMillis()
        val entries = read(ctx).toMutableList()
        var changed = false
        for (i in entries.indices) {
            val e = entries[i]
            val last = e.sessions.lastOrNull()
            if (last != null && last.leftMs == 0L) {
                val s = e.sessions.toMutableList()
                s[s.size - 1] = last.copy(leftMs = now)
                entries[i] = e.copy(sessions = s)
                changed = true
            }
        }
        if (changed) persist(ctx, entries)
    }

    /** Past-24h instances, current first then most-recently-left. Sessions within each
     *  entry stay chronological (newest last); the UI reverses them for display. */
    fun list(ctx: Context): List<Entry> =
        read(ctx).sortedByDescending { sortKey(it) }

    /** Records the world image URL for [location] (no-op when the location isn't in
     *  the 24h history). Called when the instance picker first resolves the info so
     *  the image persists (via AlertImageStore) for the entry's lifetime. */
    fun updateImage(ctx: Context, location: String, imageUrl: String) {
        if (location.isBlank() || imageUrl.isBlank()) return
        val entries = read(ctx).toMutableList()
        val idx = entries.indexOfFirst { it.location == location }
        if (idx < 0) return
        if (entries[idx].imageUrl == imageUrl) return
        entries[idx] = entries[idx].copy(imageUrl = imageUrl)
        persist(ctx, entries)
    }

    /** An entry sorts by its newest session: still-current floats to the top. */
    private fun sortKey(e: Entry): Long =
        e.sessions.maxOfOrNull { if (it.leftMs == 0L) Long.MAX_VALUE else it.leftMs } ?: 0L

    private fun read(ctx: Context): List<Entry> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val arr = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        val out = mutableListOf<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Skip any object that isn't the sessions shape (defensive — no public
            // user ever wrote an older format, but a stray blob must not crash us).
            val sArr = o.optJSONArray("sessions") ?: continue
            val sessions = mutableListOf<Session>()
            for (k in 0 until sArr.length()) {
                val so = sArr.optJSONObject(k) ?: continue
                val left = so.optLong("l")
                // Prune sessions left more than 24h ago; keep anything still current.
                if (left != 0L && left < cutoff) continue
                sessions.add(Session(joinedMs = so.optLong("j"), leftMs = left))
            }
            if (sessions.isEmpty()) continue
            out += Entry(
                location = o.optString("loc"),
                worldName = o.optString("world"),
                sessions = sessions,
                imageUrl = o.optString("img")
            )
        }
        return out
    }

    private fun persist(ctx: Context, entries: List<Entry>) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val arr = JSONArray()
        for (e in entries) {
            val kept = e.sessions.filter { it.leftMs == 0L || it.leftMs >= cutoff }
            if (kept.isEmpty()) continue
            val sArr = JSONArray()
            for (s in kept) {
                sArr.put(JSONObject().apply {
                    put("j", s.joinedMs)
                    put("l", s.leftMs)
                })
            }
            arr.put(JSONObject().apply {
                put("loc", e.location)
                put("world", e.worldName)
                put("sessions", sArr)
                put("img", e.imageUrl)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }
}
