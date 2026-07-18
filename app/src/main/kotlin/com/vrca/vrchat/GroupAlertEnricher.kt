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
        // CONFIRMED from a real /calendar/{g}/{e} response: the user's follow state
        // is the NESTED `userInterest.isFollowing` boolean (userInterest is always a
        // non-null object with createdAt/updatedAt, so the old "userInterest present
        // => following" test reported EVERY event as signed-up).
        ev.optJSONObject("userInterest")?.let { ui ->
            if (ui.has("isFollowing")) return ui.optBoolean("isFollowing", false)
        }
        for (k in listOf("isFollowing", "following", "isInterested", "isAttending", "hasJoined", "isGoing")) {
            if (ev.has(k)) {
                val v = ev.opt(k)
                if (v is Boolean) return v
            }
        }
        return null
    }

    /** VRChat's stable per-SERIES id off a calendar-event object (every occurrence
     *  of a repeat shares it; a genuine one-off has its own). null when absent. */
    fun extractSeriesId(ev: JSONObject): String? =
        ev.optString("seriesId", "").takeIf { it.isNotBlank() && it != "null" }

    private fun normTitle(ev: JSONObject): String =
        normTitleStr(ev.optString("title", "").ifBlank { ev.optString("name", "") })

    /** Normalize a plain title string for cross-path matching (shared with the
     *  alert store's dedup). */
    private fun normTitleStr(t: String?): String =
        t?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()

    /**
     * Collapse recurring occurrences (same title) to ONE representative per series
     * — the nearest UPCOMING occurrence (or most-recent past if none upcoming).
     * Returns (representative, isRecurringSeries). Single-title events pass through
     * with isRecurringSeries=false. Keeps the enricher's per-event work (single
     * fetch) bounded to ~1 per series instead of one per flooded occurrence.
     */
    private fun collapseEventsByTitle(events: JSONArray): List<Pair<JSONObject, Boolean>> {
        val now = System.currentTimeMillis()
        val buckets = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until events.length()) {
            val ev = events.optJSONObject(i) ?: continue
            val evId = ev.optString("id", "").ifBlank { i.toString() }
            // Group by `seriesId` (every occurrence of a repeat shares it) else by
            // normalized title (repeats share that too). Deliberately NOT keyed on
            // `occurrenceKind`: the calendar LIST endpoint reports it unreliably
            // (the confirmed field is on the single-event fetch), and keying it as
            // "single -> unique bucket" BROKE the collapse — every occurrence became
            // its own bucket, so the whole series flooded the fire path AND the
            // enricher did one single-event fetch per occurrence (~50) instead of
            // ONE, which is the "grabs all repeats before it loads the latest"
            // slowness. A genuine one-off has its OWN seriesId (or a unique title)
            // so it still keeps its own bucket; only a seriesless one-off sharing a
            // repeat's EXACT title could mis-merge (rare, harmless). The
            // one-off-falsely-flagged-recurring fix lives in extractRecurring (the
            // chip), which is where it actually mattered.
            val series = ev.optString("seriesId", "").takeIf { it.isNotBlank() && it != "null" }
            val key = series?.let { "s:$it" } ?: normTitle(ev).ifBlank { "id:$evId" }
            buckets.getOrPut(key) { mutableListOf() }.add(ev)
        }
        return buckets.values.map { occ ->
            if (occ.size == 1) {
                // A lone item is recurring ONLY if VRChat says so (occurrenceKind);
                // a one-off must not be flagged just for sharing a bucket.
                occ[0] to occ[0].optString("occurrenceKind", "").equals("occurrence", true)
            } else {
                val rep = occ.filter { parseTimestampMs(it.optString("startsAt", "")) >= now }
                    .minByOrNull { parseTimestampMs(it.optString("startsAt", "")) }
                    ?: occ.maxByOrNull { parseTimestampMs(it.optString("startsAt", "")) }
                    ?: occ[0]
                rep to true
            }
        }
    }

    /**
     * Public: collapse a raw calendar-events array to ONE representative per
     * series (the nearest UPCOMING occurrence) so callers never FIRE or enrich
     * every future repeat of a recurring event — only the latest. Distinct
     * events (different seriesId/title) each pass through unchanged, so OTHER
     * events from the same group are still detected. Used by the pipeline's
     * backfill + poll fire loops so a newly-created repeating series surfaces as
     * a single notification instead of one card per occurrence.
     */
    fun collapseToRepresentatives(events: JSONArray): List<JSONObject> =
        collapseEventsByTitle(events).map { it.first }

    /** Whether the event repeats (part of a series). */
    fun extractRecurring(ev: JSONObject): Boolean {
        // `occurrenceKind` is the AUTHORITATIVE recurrence signal (confirmed
        // on-device): "occurrence" = part of a repeating series, "single" = a
        // genuine one-off. Trust it EXCLUSIVELY when present — a "single" is never
        // recurring even though VRChat still stamps it with a seriesId. That stray
        // seriesId on one-offs was the cause of a separate event being falsely
        // flagged as repeating whenever a real repeating event existed in the same
        // group.
        val kind = ev.optString("occurrenceKind", "")
        if (kind.equals("occurrence", true)) return true
        if (kind.equals("single", true)) return false
        if (ev.optBoolean("isRecurring", false)) return true
        // occurrenceKind absent (a sparse list item): only a real recurrence RULE
        // is a positive signal. Bare seriesId/parentId presence is deliberately
        // NOT probed — every event (one-offs included) carries a seriesId, so
        // treating its presence as recurring false-flagged singles.
        for (k in listOf("recurrence", "recurrenceRule", "repeatType")) {
            val v = ev.opt(k) ?: continue
            if (v is Boolean && v) return true
            if (v is String && v.isNotBlank() && v.lowercase() != "none" && v.lowercase() != "never") return true
            if (v is JSONObject && v.length() > 0) return true
        }
        return false
    }

    /** The organizer id — a `usr_` (a person) OR a `grp_` (the owning group).
     *  CONFIRMED: a group calendar event's `ownerId` is the GROUP (`grp_…`), which
     *  VRChat's site shows as the event's host ("Test | Group"); a personal event
     *  would carry a `usr_`. The caller resolves the name + links to the right page
     *  (group vs user) by the prefix. */
    fun extractOrganizerId(ev: JSONObject): String? {
        for (k in listOf("ownerId", "authorId", "creatorId", "hostId", "userId")) {
            val v = ev.optString(k, "")
            if (v.startsWith("usr_") || v.startsWith("grp_")) return v
        }
        // Nested owner/author object.
        for (k in listOf("owner", "author", "creator", "host")) {
            val o = ev.optJSONObject(k) ?: continue
            val v = o.optString("id", "")
            if (v.startsWith("usr_") || v.startsWith("grp_")) return v
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
    suspend fun enrich(
        ctx: Context,
        groupId: String,
        minIntervalMs: Long = 60_000L,
        // Re-surface a dismissed followed event ONLY when this is a post-fire sweep
        // (the genuine "you subscribed/created and swiped it away too fast" window).
        // The routine periodic sweep must NOT re-add dismissed events — VRChat
        // reports the user's OWN events as followed forever, so a periodic
        // re-surface brought every dismissed self-created event back on every tab
        // open (the "old dismissed events keep reappearing in Going" bug). Default
        // false = update existing cards only.
        allowResurface: Boolean = false
    ): Boolean {
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
            return enrichLocked(ctx, groupId, allowResurface)
        } finally {
            sweepMutex.unlock()
        }
    }

    /**
     * Detects DELETED events for [groupId] (followed OR not) and flags their cards
     * "Removed". A card is a candidate when it's a not-yet-removed event whose series
     * is ABSENT from the freshly-fetched live calendar ([liveKeys]) AND it's still
     * RELEVANT — signed up ([following]==true), UPCOMING, or unknown-timing. A clearly
     * PAST non-followed event is skipped (it ended, it wasn't "removed", and
     * re-confirming it every sweep is waste). A single tri-state fetch then CONFIRMS
     * the deletion — only a real 404 flags it; a network / 429 / 5xx blip is UNKNOWN
     * and flags nothing. Bounded: deduped per series and capped so a large group
     * can't burst REST calls. Absent-from-list detection itself is FREE (the enricher
     * already fetched that list to update every visible card), so the only added cost
     * is the handful of 404-confirm fetches for events that actually went missing.
     */
    private suspend fun detectRemovedEvents(ctx: Context, groupId: String, liveKeys: Set<String>) {
        val groupKey = "event_$groupId"
        val g = InAppAlertState.groups.value.firstOrNull { it.groupId == groupKey } ?: return
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val candidates = ArrayList<InAppAlertEvent>()
        for (e in g.events) {
            if (e.removed) continue
            val evId = e.eventRefId ?: continue
            // ENDED events are handled by the prune (they just vanish); the red
            // "Removed" state is reserved for an event you might still attend that
            // got cancelled. So only confirm NON-ended events here.
            if (InAppAlertState.eventEnded(e, now)) continue
            val titleKey = normTitleStr(e.eventTitle).takeIf { it.isNotBlank() }?.let { "t:$it" }
            val present = liveKeys.contains("c:$evId") ||
                (e.seriesId != null && liveKeys.contains("s:${e.seriesId}")) ||
                (titleKey != null && liveKeys.contains(titleKey))
            if (present) continue
            // Dedup per SERIES: seriesId, else normalized title (repeating
            // occurrences share it — this is what makes a deleted repeating series
            // cost ONE confirm fetch instead of one per flooded occurrence), else
            // the occurrence id.
            val dedup = e.seriesId?.let { "s:$it" } ?: titleKey ?: "c:$evId"
            if (!seen.add(dedup)) continue
            candidates.add(e)
            if (candidates.size >= 10) break
        }
        for (e in candidates) {
            val evId = e.eventRefId ?: continue
            val res = VrchatAuthManager.fetchCalendarEventResult(ctx, groupId, evId)
            if (res.status == VrchatAuthManager.CalendarEventStatus.DELETED) {
                InAppAlertState.markSeriesRemoved(
                    ctx, groupKey, e.seriesId, evId, normTitleStr(e.eventTitle)
                )
            }
            kotlinx.coroutines.delay(250)
        }
    }

    private suspend fun enrichLocked(ctx: Context, groupId: String, allowResurface: Boolean): Boolean {
        try {
            // ---- Calendar events: match alerts by the cal_ id ----
            // Collapse recurring occurrences to ONE representative per series FIRST,
            // so the per-representative single-event fetch (below) is bounded to
            // ~1 call per series instead of one per flooded occurrence.
            val events = VrchatAuthManager.fetchGroupCalendarEvents(ctx, groupId, 20)
            // Series keys present in the LIVE calendar right now (by seriesId,
            // cal_ id, and normalized title). Used below to detect a card whose
            // event VRChat no longer lists — a deletion candidate confirmed by a
            // 404 single-fetch before we flag it "Removed". Built even when the list
            // is null/empty (an ALL-events-deleted group returns null): detection
            // then runs with an empty set, and the per-event 404-confirm is the
            // authoritative signal — a network blip returns UNKNOWN and flags
            // nothing, so running on null is safe. This is the fix for a deleted
            // followed event in an emptied group never flipping to "Removed".
            val liveKeys = HashSet<String>()
            if (events != null) {
                for (i in 0 until events.length()) {
                    val e = events.optJSONObject(i) ?: continue
                    extractSeriesId(e)?.let { liveKeys.add("s:$it") }
                    e.optString("id", "").ifBlank { findIdWithPrefix(e, "cal_").orEmpty() }
                        .takeIf { it.isNotBlank() }?.let { liveKeys.add("c:$it") }
                    normTitle(e).takeIf { it.isNotBlank() }?.let { liveKeys.add("t:$it") }
                }
            }
            detectRemovedEvents(ctx, groupId, liveKeys)
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
                    // The group calendar LIST omits the user's per-event follow
                    // state, so an event added IN-GAME wasn't detected. Fetch the
                    // single event (which carries it) to get authoritative
                    // following + richer organizer/recurrence. One call per
                    // representative (after recurring-collapse, ~1 per series).
                    val single = VrchatAuthManager.fetchCalendarEvent(ctx, groupId, evId)
                    val src = single ?: ev
                    // Stable per-series id (single event first, then list item).
                    val seriesId = extractSeriesId(src) ?: extractSeriesId(ev)
                    // Interested count comes from the SINGLE event first — VRChat's
                    // group-calendar LIST count lags (it didn't move when the user
                    // added/removed even though the website updated instantly),
                    // while the single-event object carries the live count the site
                    // shows. Fall back to the list only if the single fetch failed.
                    val interested = extractInterestedCount(src).takeIf { it >= 0 }
                        ?: extractInterestedCount(ev)
                    // The user's explicit in-app follow decision is AUTHORITATIVE and
                    // permanent — it wins over the server read, which for the user's
                    // OWN events perpetually reports following=true (so removing an
                    // event would otherwise flip straight back to joined every sweep).
                    // Keyed per SERIES so a removed recurring event stays removed.
                    val followKey = InAppAlertState.followSeriesKey(
                        repTitle.ifBlank { ev.optString("title", "").ifBlank { ev.optString("name", "") } },
                        evId
                    )
                    val following = InAppAlertState.followOverride(followKey)
                        ?: extractEventFollowing(src) ?: extractEventFollowing(ev)
                    val recurring = isRecurringSeries || extractRecurring(src) || extractRecurring(ev)
                    // Host/organizer is deliberately NOT shown on notifications, so
                    // we don't extract or resolve it (avoids a per-event REST call).
                    val organizerId: String? = null
                    val organizerName: String? = null
                    val title = ev.optString("title", "").ifBlank { ev.optString("name", "") }
                    val desc = ev.optString("description", "").ifBlank { ev.optString("text", "") }
                    if (img != null) AlertImageStore.ensureCached(ctx, img)
                    InAppAlertState.enrichEvents(
                        ctx, "event_$groupId",
                        match = { e ->
                            // SERIES id is the strongest, title-independent signal —
                            // it keeps two DISTINCT series from cross-matching (which
                            // mixed their banners/languages/descriptions and made a
                            // card flip-flop between them).
                            (seriesId != null && e.seriesId == seriesId) ||
                                e.eventRefId == evId ||
                                (e.eventRefId == null && e.url?.contains(evId) == true) ||
                                // Adopt a THIN v2 card (fired before its cal_ id
                                // was known — the common freshly-created-event
                                // case) by its event title, so this sweep upgrades
                                // it IN PLACE (banner/timing/following) instead of
                                // leaving it dangling while the REST sweep fires a
                                // duplicate. The transform below links its
                                // eventRefId/seriesId, so it's only adopted once.
                                (e.eventRefId == null && e.seriesId == null &&
                                    repTitle.isNotBlank() && normTitleStr(e.eventTitle) == repTitle) ||
                                // A recurring representative updates ALL stored
                                // occurrences of its series. Prefer seriesId; fall
                                // back to title only for cards that have no seriesId
                                // yet (pre-update / thin), so a titled sibling series
                                // can't be dragged in once ids are known.
                                (recurring && e.recurring && e.seriesId == null &&
                                    repTitle.isNotBlank() && normTitleStr(e.eventTitle) == repTitle)
                        },
                        transform = { e ->
                            e.copy(
                                imageUrl = img ?: e.imageUrl,
                                groupRefId = e.groupRefId ?: groupId,
                                eventRefId = evId,
                                seriesId = seriesId ?: e.seriesId,
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
                                // NOTE: deliberately do NOT clear `removed` here. A
                                // title-fallback match could otherwise un-remove a
                                // DELETED same-title event (e.g. two repeating series
                                // in one group). `removed` is set only on a confirmed
                                // 404 and stays sticky until the user dismisses it; a
                                // genuinely recreated event gets a fresh id → new card.
                            )
                        }
                    )
                    // ---- Re-surface a followed event whose card was swiped away ----
                    // ONLY on a post-fire sweep (allowResurface): the genuine
                    // "you subscribed/created and swiped it away too fast" window.
                    // The routine periodic sweep must NOT re-add dismissed events —
                    // VRChat reports the user's OWN events as followed forever, so a
                    // periodic re-surface resurrected every dismissed self-created
                    // event on each tab open. Remove-from-Calendar sets
                    // following=false, so an intentional un-follow is never fought.
                    if (allowResurface && following == true &&
                        !InAppAlertState.hasSeriesCard("event_$groupId", seriesId, evId, repTitle)) {
                        val gTitle = InAppAlertState.groupTitleOf("event_$groupId")
                            ?: ("Event from " + (VrchatAuthManager.fetchGroupName(ctx, groupId) ?: "a group"))
                        InAppAlertState.readdFollowedEventIfMissing(
                            ctx, "event_$groupId", gTitle,
                            InAppAlertEvent(
                                id = "event_${groupId}_resurf_$evId",
                                body = if (startsMs > 0 && desc.isNotBlank()) desc else title,
                                timestampMs = if (startsMs > 0) startsMs else createdMs,
                                eventTitle = title.ifBlank { null },
                                url = "https://vrchat.com/home/group/$groupId/calendar/$evId",
                                imageUrl = img,
                                groupRefId = groupId,
                                eventRefId = evId,
                                seriesId = seriesId,
                                startsAtMs = startsMs,
                                endsAtMs = endsMs,
                                createdAtMs = createdMs,
                                interestedCount = interested,
                                category = category.ifBlank { null },
                                platforms = platformsCsv,
                                accessType = access.ifBlank { null },
                                languages = languagesCsv,
                                following = true,
                                recurring = recurring
                            )
                        )
                    }
                }
            }
            // ---- Posts/announcements: match alerts by normalized body ----
            // Same 250ms pacing the backfill uses between REST calls.
            kotlinx.coroutines.delay(250)
            val posts = VrchatAuthManager.fetchGroupPosts(ctx, groupId, 10)
            if (posts != null) {
                for (j in 0 until posts.length()) {
                    val post = posts.optJSONObject(j) ?: continue
                    val text = post.optString("text", "")
                    val title = post.optString("title", "")
                    val norm = normalizeAnnBody(text.ifBlank { title })
                    if (norm.isBlank()) continue
                    val img = post.optString("imageUrl", "").takeIf { it.startsWith("http") }
                    if (img != null) AlertImageStore.ensureCached(ctx, img)
                    // Enrich the banner only (host/author is deliberately not shown).
                    InAppAlertState.enrichEvents(
                        ctx, "announcement_$groupId",
                        match = { e -> e.imageUrl == null && normalizeAnnBody(e.body) == norm },
                        transform = { e -> e.copy(imageUrl = img ?: e.imageUrl, groupRefId = e.groupRefId ?: groupId) }
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
