package com.vrca.vrchat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent per-series store of a repeating event's OCCURRENCES. Backs the "Repeats"
 * dialog: it lists every occurrence, remembers which the user subscribed to, and flags
 * deletions — without re-fetching everything on each open. Keyed by "groupId|seriesId".
 *
 * Cost model (deliberate): occurrence EXISTENCE + `startsAt` come FREE from the group
 * calendar list (which the enricher already fetches), so the store is populated and
 * deletion-checked for zero extra calls. Only per-occurrence FOLLOW state costs a REST
 * call, so `following` starts null (unknown) and is filled lazily (dialog-open, paced,
 * nearest-first) or written directly when the user subscribes in-app.
 *
 * Lifecycle: an occurrence lives here until it ENDS (pruned) or is DELETED (flagged
 * `deleted=true`, kept [DELETED_KEEP_MS] so the user sees it went away, then pruned).
 */
object EventSeriesStore {
    private const val PREFS = "vrca_event_series"
    private const val KEY = "series_json"
    private const val LIVE_TAIL_MS = 4L * 60 * 60 * 1000       // live for 4h past start when no end
    private const val ENDED_KEEP_MS = 60L * 60 * 1000          // keep an ended occurrence 1h then prune
    private const val DELETED_KEEP_MS = 24L * 60 * 60 * 1000   // keep a deleted occurrence visible 24h

    data class Occurrence(
        val id: String,               // cal_ occurrence id
        val startsAtMs: Long,
        val endsAtMs: Long,
        val following: Boolean?,      // null = follow state not yet known (unfetched)
        val followCheckedMs: Long,    // when `following` was last confirmed from the server
        val deleted: Boolean,
        val deletedAtMs: Long
    ) {
        fun ended(now: Long): Boolean = when {
            endsAtMs > 0L -> endsAtMs < now
            startsAtMs > 0L -> now - startsAtMs > LIVE_TAIL_MS
            else -> false
        }
    }

    // seriesKey -> (occurrenceId -> Occurrence)
    private val cache = HashMap<String, LinkedHashMap<String, Occurrence>>()
    private var loaded = false

    private fun key(groupId: String, seriesId: String) = "$groupId|$seriesId"

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        try {
            val root = JSONObject(raw)
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = root.optJSONArray(k) ?: continue
                val map = LinkedHashMap<String, Occurrence>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id", "")
                    if (id.isBlank()) continue
                    map[id] = Occurrence(
                        id = id,
                        startsAtMs = o.optLong("s", 0L),
                        endsAtMs = o.optLong("e", 0L),
                        following = when (o.optInt("f", -1)) { 1 -> true; 0 -> false; else -> null },
                        followCheckedMs = o.optLong("fc", 0L),
                        deleted = o.optBoolean("d", false),
                        deletedAtMs = o.optLong("da", 0L)
                    )
                }
                if (map.isNotEmpty()) cache[k] = map
            }
        } catch (_: Throwable) { /* corrupt → start empty */ }
    }

    private fun persist(ctx: Context) {
        val root = JSONObject()
        for ((k, map) in cache) {
            if (map.isEmpty()) continue
            val arr = JSONArray()
            for (occ in map.values) {
                arr.put(JSONObject().apply {
                    put("id", occ.id)
                    put("s", occ.startsAtMs)
                    put("e", occ.endsAtMs)
                    put("f", when (occ.following) { true -> 1; false -> 0; null -> -1 })
                    put("fc", occ.followCheckedMs)
                    put("d", occ.deleted)
                    put("da", occ.deletedAtMs)
                })
            }
            root.put(k, arr)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, root.toString()).apply()
    }

    /** Drops occurrences that ended (past + grace) or were deleted long enough ago. */
    private fun pruneSeries(map: MutableMap<String, Occurrence>, now: Long): Boolean {
        val before = map.size
        val it = map.values.iterator()
        while (it.hasNext()) {
            val o = it.next()
            val stale = if (o.deleted) o.deletedAtMs > 0L && now - o.deletedAtMs > DELETED_KEEP_MS
                        else o.ended(now) && (now - maxOf(o.endsAtMs, o.startsAtMs) > ENDED_KEEP_MS)
            if (stale) it.remove()
        }
        return map.size != before
    }

    /**
     * Merges the AUTHORITATIVE current occurrence list for a series (from the group
     * calendar) into the store: adds new occurrences, refreshes timing, and flags any
     * stored-but-absent occurrence as DELETED. FREE (no follow fetch). [live] is a list
     * of (occurrenceId, startsAtMs, endsAtMs). Pass `listOk=false` on a failed/partial
     * fetch so absence can't false-flag a deletion.
     */
    @Synchronized
    fun reconcileFromList(
        ctx: Context, groupId: String, seriesId: String,
        live: List<Triple<String, Long, Long>>, listOk: Boolean
    ) {
        if (groupId.isBlank() || seriesId.isBlank()) return
        ensureLoaded(ctx)
        val now = System.currentTimeMillis()
        val map = cache.getOrPut(key(groupId, seriesId)) { LinkedHashMap() }
        val liveIds = HashSet<String>()
        for ((id, starts, ends) in live) {
            if (id.isBlank()) continue
            liveIds.add(id)
            val ex = map[id]
            map[id] = (ex ?: Occurrence(id, starts, ends, null, 0L, false, 0L)).copy(
                startsAtMs = if (starts > 0L) starts else ex?.startsAtMs ?: 0L,
                endsAtMs = if (ends > 0L) ends else ex?.endsAtMs ?: 0L,
                deleted = false, deletedAtMs = 0L   // present again → un-delete (recreated)
            )
        }
        // Flag stored-but-absent occurrences as deleted — only when the list actually
        // fetched (a null/failed list is ambiguous). Skip already-ended ones (they just
        // rolled off the calendar) AND anything BEYOND the window the list covered
        // (`maxLiveStart`) — a far-future occurrence absent because the list is capped
        // must not be mistaken for a deletion.
        if (listOk) {
            val maxLiveStart = live.maxOfOrNull { it.second } ?: 0L
            for (o in map.values.toList()) {
                val inWindow = o.startsAtMs <= 0L || maxLiveStart <= 0L || o.startsAtMs <= maxLiveStart
                if (!o.deleted && o.id !in liveIds && !o.ended(now) && inWindow) {
                    map[o.id] = o.copy(deleted = true, deletedAtMs = now)
                }
            }
        }
        pruneSeries(map, now)
        persist(ctx)
    }

    /** The series' occurrences for display: sorted by start, ended ones dropped. */
    @Synchronized
    fun occurrences(ctx: Context, groupId: String, seriesId: String): List<Occurrence> {
        ensureLoaded(ctx)
        val now = System.currentTimeMillis()
        val map = cache[key(groupId, seriesId)] ?: return emptyList()
        if (pruneSeries(map, now)) persist(ctx)
        return map.values
            .filter { it.deleted || !it.ended(now) }
            .sortedBy { if (it.startsAtMs > 0L) it.startsAtMs else Long.MAX_VALUE }
    }

    /** Records the user's subscribe/unsubscribe for one occurrence (in-app, free). */
    @Synchronized
    fun setFollowing(ctx: Context, groupId: String, seriesId: String, id: String, following: Boolean) {
        if (groupId.isBlank() || seriesId.isBlank() || id.isBlank()) return
        ensureLoaded(ctx)
        val map = cache.getOrPut(key(groupId, seriesId)) { LinkedHashMap() }
        val ex = map[id] ?: Occurrence(id, 0L, 0L, null, 0L, false, 0L)
        map[id] = ex.copy(following = following, followCheckedMs = System.currentTimeMillis())
        persist(ctx)
    }

    /** Flags a single occurrence deleted (confirmed 404) so it shows "Removed" in the
     *  dialog and drops out of [nextUpcoming]. */
    @Synchronized
    fun markOccurrenceDeleted(ctx: Context, groupId: String, seriesId: String, id: String) {
        if (groupId.isBlank() || seriesId.isBlank() || id.isBlank()) return
        ensureLoaded(ctx)
        val map = cache[key(groupId, seriesId)] ?: return
        val o = map[id] ?: return
        if (o.deleted) return
        map[id] = o.copy(deleted = true, deletedAtMs = System.currentTimeMillis())
        persist(ctx)
    }

    /** Whether the user is subscribed to at least one live occurrence of the series. */
    @Synchronized
    fun anySubscribed(ctx: Context, groupId: String, seriesId: String): Boolean {
        ensureLoaded(ctx)
        val now = System.currentTimeMillis()
        val map = cache[key(groupId, seriesId)] ?: return false
        return map.values.any { it.following == true && !it.deleted && !it.ended(now) }
    }

    /** Count of subscribed live occurrences (for the "N dates" pill). */
    @Synchronized
    fun subscribedCount(ctx: Context, groupId: String, seriesId: String): Int {
        ensureLoaded(ctx)
        val now = System.currentTimeMillis()
        val map = cache[key(groupId, seriesId)] ?: return 0
        return map.values.count { it.following == true && !it.deleted && !it.ended(now) }
    }

    /** True once EVERY known occurrence of the series is deleted (series removed). */
    @Synchronized
    fun allDeleted(ctx: Context, groupId: String, seriesId: String): Boolean {
        ensureLoaded(ctx)
        val map = cache[key(groupId, seriesId)] ?: return false
        return map.isNotEmpty() && map.values.all { it.deleted }
    }

    /** The nearest occurrence that has NOT ended (upcoming or currently live), used to
     *  ROLL a recurring card forward when its shown occurrence ends. null when the
     *  series has no more occurrences (concluded) or we have no data for it. */
    @Synchronized
    fun nextUpcoming(ctx: Context, groupId: String, seriesId: String, nowMs: Long): Occurrence? {
        ensureLoaded(ctx)
        val map = cache[key(groupId, seriesId)] ?: return null
        return map.values
            .filter { !it.deleted && !it.ended(nowMs) && it.startsAtMs > 0L }
            .minByOrNull { it.startsAtMs }
    }

    /** Occurrence ids whose follow state is unknown or older than [staleMs], nearest
     *  upcoming first — the lazy fetch targets (bounded by the caller). */
    @Synchronized
    fun idsNeedingFollow(ctx: Context, groupId: String, seriesId: String, staleMs: Long): List<String> {
        ensureLoaded(ctx)
        val now = System.currentTimeMillis()
        val map = cache[key(groupId, seriesId)] ?: return emptyList()
        return map.values
            .filter { !it.deleted && !it.ended(now) &&
                (it.following == null || now - it.followCheckedMs > staleMs) }
            .sortedBy { if (it.startsAtMs > 0L) it.startsAtMs else Long.MAX_VALUE }
            .map { it.id }
    }
}
