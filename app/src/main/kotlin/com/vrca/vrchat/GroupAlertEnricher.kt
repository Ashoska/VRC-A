package com.vrca.vrchat

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * One-group REST enrichment for event/announcement alerts, shared by the
 * pipeline service (thin notification-v2 payloads right after they fire) and the
 * VRChat tab (a periodic refresh while alerts are on screen, so the interested
 * count, edited times/descriptions, banners, and follow state UPDATE IN PLACE
 * while the notification exists — previously the card froze at fire-time data).
 *
 * Fetches the group's calendar events + posts (2 REST calls, debounced per
 * group) and merges fresh values into the matching alert events via
 * [InAppAlertState.enrichEvents]. NEVER fires a notification — display only.
 */
object GroupAlertEnricher {
    private const val TAG = "GroupAlertEnricher"
    private val lastSweepMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** VRChat timestamp → epoch ms (same parsing as the pipeline service). */
    fun parseTimestampMs(ts: String): Long {
        if (ts.isBlank()) return 0L
        return try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val cleaned = ts.replace(Regex("\\.[0-9]+Z?$"), "").removeSuffix("Z")
            fmt.parse(cleaned)?.time ?: 0L
        } catch (_: Exception) { 0L }
    }

    /** Event banner URL off a calendar-event object (field name varies). */
    fun extractEventImageUrl(ev: JSONObject): String? =
        ev.optString("imageUrl", "")
            .ifBlank { ev.optString("imageURL", "") }
            .ifBlank { ev.optString("bannerUrl", "") }
            .ifBlank { ev.optString("thumbnailUrl", "") }
            .takeIf { it.startsWith("http") }

    /** "Interested people" count off a calendar-event object. -1 = unknown. */
    fun extractInterestedCount(ev: JSONObject): Int {
        val direct = ev.optInt("interestedUserCount", -1)
        if (direct >= 0) return direct
        val alt = ev.optInt("interestedCount", -1)
        if (alt >= 0) return alt
        return ev.optJSONObject("interested")?.optInt("count", -1) ?: -1
    }

    /** Whether the USER has this event on their calendar (signed up / interested) —
     *  so an event added IN-GAME shows as signed-up and the button reads "Remove
     *  from Calendar" instead of wrongly offering "Add". VRChat's exact field isn't
     *  documented, so probe the likely BOOLEAN names (an int like `interested` is a
     *  count, not the follow state, so only Boolean values count); `userInterest`
     *  present-and-non-null is the fallback signal. null = the object didn't say. */
    fun extractEventFollowing(ev: JSONObject): Boolean? {
        for (k in listOf("isFollowing", "following", "isInterested", "isAttending", "hasJoined", "isGoing")) {
            if (ev.has(k)) {
                val v = ev.opt(k)
                if (v is Boolean) return v
            }
        }
        if (ev.has("userInterest")) return !ev.isNull("userInterest")
        return null
    }

    private fun normTitle(ev: JSONObject): String =
        ev.optString("title", "").ifBlank { ev.optString("name", "") }
            .trim().lowercase().replace(Regex("\\s+"), " ")

    /**
     * Collapse recurring occurrences (same title) to ONE representative per series
     * — the nearest UPCOMING occurrence (or most-recent past if none upcoming).
     * Returns (representative, isRecurringSeries). Single-title events pass through
     * with isRecurringSeries=false. Keeps the enricher's per-event work (single
     * fetch) bounded to ~1 per series instead of one per flooded occurrence.
     */
    private fun collapseEventsByTitle(events: JSONArray): List<Pair<JSONObject, Boolean>> {
        val now = System.currentTimeMillis()
        val byTitle = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val t = normTitle(ev).ifBlank { "id:${ev.optString("id", i.toString())}" }
            byTitle.getOrPut(t) { mutableListOf() }.add(ev)
        }
        return byTitle.values.map { occ ->
            if (occ.size == 1) occ[0] to false
            else {
                val rep = occ.filter { parseTimestampMs(it.optString("startsAt", "")) >= now }
                    .minByOrNull { parseTimestampMs(it.optString("startsAt", "")) }
                    ?: occ.maxByOrNull { parseTimestampMs(it.optString("startsAt", "")) }
                    ?: occ[0]
                rep to true
            }
        }
    }

    /** Best-effort: whether the event repeats (part of a series). VRChat's exact
     *  recurrence field isn't documented, so probe the likely names — any
     *  non-empty/true value means recurring; absent → false (chip just won't show). */
    fun extractRecurring(ev: JSONObject): Boolean {
        if (ev.optBoolean("isRecurring", false)) return true
        for (k in listOf("recurrence", "recurrenceRule", "repeatType", "recurringId",
                "seriesId", "parentId", "parentCalendarEventId", "recurrenceId")) {
            val v = ev.opt(k) ?: continue
            if (v is Boolean && v) return true
            if (v is String && v.isNotBlank() && v.lowercase() != "none" && v.lowercase() != "never") return true
            if (v is JSONObject && v.length() > 0) return true
        }
        return false
    }

    /** The organizer's user id (`usr_...`) — the person who created the event,
     *  distinct from the hosting group. Probes the likely owner fields. */
    fun extractOrganizerId(ev: JSONObject): String? {
        for (k in listOf("ownerId", "authorId", "creatorId", "hostId", "userId")) {
            val v = ev.optString(k, "")
            if (v.startsWith("usr_")) return v
        }
        // Nested owner/author object.
        for (k in listOf("owner", "author", "creator", "host")) {
            val o = ev.optJSONObject(k) ?: continue
            val v = o.optString("id", "")
            if (v.startsWith("usr_")) return v
        }
        return null
    }

    /** Organizer display name straight off the event object (a nested owner
     *  object sometimes carries it), so we can skip the extra fetch. */
    fun extractOrganizerName(ev: JSONObject): String? {
        for (k in listOf("owner", "author", "creator", "host")) {
            val o = ev.optJSONObject(k) ?: continue
            val n = o.optString("displayName", "").ifBlank { o.optString("name", "") }
            if (n.isNotBlank()) return n
        }
        return ev.optString("ownerDisplayName", "").ifBlank { null }
    }

    // Resolved organizer names, keyed by usr_ id (process-lifetime).
    private val organizerNameCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun jsonArrayToCsv(arr: JSONArray?): String? {
        if (arr == null || arr.length() == 0) return null
        val parts = (0 until arr.length()).mapNotNull { arr.optString(it).ifBlank { null } }
        return parts.joinToString(",").ifBlank { null }
    }

    /** Strips VRChat's notification-v2 title boilerplate ("New event by Test:
     *  Hallo :D" → "Hallo :D") so the alert shows the actual EVENT name. */
    fun cleanEventTitle(raw: String): String =
        raw.replace(Regex("^New (event|post|announcement) (by|from) [^:]{0,60}:\\s*"), "").trim()

    /** Normalized announcement body for enrichment matching — same normalization
     *  as the pipeline's content fingerprint so a REST post matches a v2 alert. */
    private fun normalizeAnnBody(s: String): String =
        s.trim().lowercase().replace(Regex("\\s+"), " ").take(120)

    // Recursive cal_/grp_ prefix scanner (copy of the pipeline's — payloads bury
    // ids in inconsistent places).
    private fun findIdWithPrefix(node: Any?, prefix: String, depth: Int = 0): String? {
        if (depth > 6 || node == null) return null
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (v is String && v.startsWith(prefix)) return v
                    findIdWithPrefix(v, prefix, depth + 1)?.let { return it }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    findIdWithPrefix(node.opt(i), prefix, depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    // Global cross-group guard: all sweeps run ONE AT A TIME with a minimum
    // spacing between them. The per-group debounce alone left a hole — a user
    // with cards from several DIFFERENT groups spamming expand across them got
    // one unspaced burst per distinct group. With the mutex + spacing, worst
    // case is one 2-call sweep every 2.5s no matter how many cards are poked;
    // pending sweeps queue (bounded by the distinct-group count) instead of
    // firing concurrently.
    private val sweepMutex = kotlinx.coroutines.sync.Mutex()
    @Volatile private var lastAnySweepMs = 0L
    private const val GLOBAL_SPACING_MS = 2_500L

    /**
     * Sweeps [groupId]'s calendar events + posts and merges fresh values into the
     * matching alert events. Debounced per group ([minIntervalMs]) AND globally
     * serialized/spaced across groups; returns true when a sweep actually ran.
     */
    suspend fun enrich(ctx: Context, groupId: String, minIntervalMs: Long = 60_000L): Boolean {
        if (groupId.isBlank()) return false
        // Atomic per-group debounce check+stamp (two concurrent calls for the
        // same group can't both pass).
        val now = System.currentTimeMillis()
        var proceed = false
        lastSweepMs.compute(groupId) { _, last ->
            if (last == null || now - last >= minIntervalMs) { proceed = true; now } else last
        }
        if (!proceed) return false
        sweepMutex.lock()
        try {
            val sinceLast = System.currentTimeMillis() - lastAnySweepMs
            if (sinceLast < GLOBAL_SPACING_MS) {
                kotlinx.coroutines.delay(GLOBAL_SPACING_MS - sinceLast)
            }
            lastAnySweepMs = System.currentTimeMillis()
            return enrichLocked(ctx, groupId)
        } finally {
            sweepMutex.unlock()
        }
    }

    private suspend fun enrichLocked(ctx: Context, groupId: String): Boolean {
        try {
            // ---- Calendar events: match alerts by the cal_ id ----
            // Collapse recurring occurrences to ONE representative per series FIRST,
            // so the per-representative single-event fetch (below) is bounded to
            // ~1 call per series instead of one per flooded occurrence.
            val events = VrchatAuthManager.fetchGroupCalendarEvents(ctx, groupId, 20)
            if (events != null) {
                for ((ev, isRecurringSeries) in collapseEventsByTitle(events)) {
                    val evId = ev.optString("id", "").ifBlank { findIdWithPrefix(ev, "cal_").orEmpty() }
                    if (evId.isBlank()) continue
                    val repTitle = normTitle(ev)
                    val startsMs = parseTimestampMs(ev.optString("startsAt", ""))
                    val endsMs = parseTimestampMs(ev.optString("endsAt", ""))
                    val createdMs = parseTimestampMs(ev.optString("createdAt", ""))
                    val img = extractEventImageUrl(ev)
                    val category = ev.optString("category", "")
                    val platformsCsv = jsonArrayToCsv(ev.optJSONArray("platforms"))
                    val languagesCsv = jsonArrayToCsv(ev.optJSONArray("languages"))
                    val access = ev.optString("accessType", "")
                    val interested = extractInterestedCount(ev)
                    // The group calendar LIST omits the user's per-event follow
                    // state, so an event added IN-GAME wasn't detected. Fetch the
                    // single event (which carries it) to get authoritative
                    // following + richer organizer/recurrence. One call per
                    // representative (after recurring-collapse, ~1 per series).
                    val single = VrchatAuthManager.fetchCalendarEvent(ctx, groupId, evId)
                    val src = single ?: ev
                    // A just-toggled follow state (Add/Remove tapped seconds ago)
                    // wins over the server read, which may not have propagated yet
                    // — otherwise removing an event flips straight back to joined.
                    val following = InAppAlertState.recentFollowOverride(evId)
                        ?: extractEventFollowing(src) ?: extractEventFollowing(ev)
                    val recurring = isRecurringSeries || extractRecurring(src) || extractRecurring(ev)
                    val organizerId = extractOrganizerId(src) ?: extractOrganizerId(ev)
                    // Organizer name: prefer a name on the event object; else resolve
                    // once via /users/{id} and cache (one call per unique organizer).
                    val organizerName = extractOrganizerName(src) ?: extractOrganizerName(ev)
                        ?: organizerId?.let { oid ->
                            organizerNameCache[oid] ?: VrchatAuthManager.fetchUserDisplayName(ctx, oid)
                                ?.also { organizerNameCache[oid] = it }
                        }
                    val title = ev.optString("title", "").ifBlank { ev.optString("name", "") }
                    val desc = ev.optString("description", "").ifBlank { ev.optString("text", "") }
                    if (img != null) AlertImageStore.ensureCached(ctx, img)
                    InAppAlertState.enrichEvents(
                        ctx, "event_$groupId",
                        match = { e ->
                            e.eventRefId == evId ||
                                (e.eventRefId == null && e.url?.contains(evId) == true) ||
                                // A recurring representative updates ALL stored
                                // occurrences of its series (matched by title) so
                                // following/interested/etc. propagate to every one
                                // and the display-collapse shows consistent data.
                                (recurring && e.recurring && repTitle.isNotBlank() &&
                                    (e.eventTitle?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: "") == repTitle)
                        },
                        transform = { e ->
                            e.copy(
                                imageUrl = img ?: e.imageUrl,
                                groupRefId = e.groupRefId ?: groupId,
                                eventRefId = evId,
                                startsAtMs = if (startsMs > 0) startsMs else e.startsAtMs,
                                endsAtMs = if (endsMs > 0) endsMs else e.endsAtMs,
                                createdAtMs = if (createdMs > 0) createdMs else e.createdAtMs,
                                interestedCount = if (interested >= 0) interested else e.interestedCount,
                                category = category.ifBlank { e.category.orEmpty() }.ifBlank { null },
                                platforms = platformsCsv ?: e.platforms,
                                accessType = access.ifBlank { e.accessType.orEmpty() }.ifBlank { null },
                                // Languages are the one field an edit can REMOVE
                                // entirely: when the object carries the key, its
                                // (possibly empty) value is authoritative — an
                                // emptied list must CLEAR the chip, not keep stale.
                                languages = if (ev.has("languages")) languagesCsv else e.languages,
                                following = following ?: e.following,
                                recurring = recurring || e.recurring,
                                organizerId = organizerId ?: e.organizerId,
                                organizerName = organizerName ?: e.organizerName,
                                // Upgrade the display: the event's REAL name (drops the
                                // "New event by X:" v2 boilerplate), timestamp = start,
                                // body = clean description. Edits made on VRChat's side
                                // (time/description) land here on the next sweep.
                                eventTitle = title.ifBlank { e.eventTitle },
                                timestampMs = if (startsMs > 0) startsMs else e.timestampMs,
                                body = if (startsMs > 0 && desc.isNotBlank()) desc else e.body
                            )
                        }
                    )
                }
            }
            // ---- Posts/announcements: match alerts by normalized body ----
            // Same 250ms pacing the backfill uses between REST calls.
            kotlinx.coroutines.delay(250)
            val posts = VrchatAuthManager.fetchGroupPosts(ctx, groupId, 10)
            if (posts != null) {
                for (j in 0 until posts.length()) {
                    val post = posts.optJSONObject(j) ?: continue
                    val img = post.optString("imageUrl", "").takeIf { it.startsWith("http") } ?: continue
                    val text = post.optString("text", "")
                    val title = post.optString("title", "")
                    val norm = normalizeAnnBody(text.ifBlank { title })
                    if (norm.isBlank()) continue
                    AlertImageStore.ensureCached(ctx, img)
                    InAppAlertState.enrichEvents(
                        ctx, "announcement_$groupId",
                        match = { e -> e.imageUrl == null && normalizeAnnBody(e.body) == norm },
                        transform = { e -> e.copy(imageUrl = img, groupRefId = e.groupRefId ?: groupId) }
                    )
                }
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "enrich($groupId) failed", e)
            return false
        }
    }
}
