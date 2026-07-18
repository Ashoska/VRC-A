package com.vrca.vrchat

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class InAppAlertEvent(
    val id: String,
    val body: String,
    val beforeText: String? = null,
    val afterText: String? = null,
    val timestampMs: Long,
    val eventTitle: String? = null,
    val url: String? = null,
    // Optional in-app action button (e.g. re-invite). actionType is one of
    // "invite_me" (actionData = instance location) or "invite_user"
    // (actionData = the requester's userId; the instance is read live at tap).
    val actionType: String? = null,
    val actionData: String? = null,
    // ---- Rich event/announcement metadata (all optional, additive) ----
    // Banner image URL (event banner / group post image / world thumb). The
    // actual bytes are persisted by AlertImageStore until this event is
    // dismissed, so the UI never re-downloads on menu open.
    val imageUrl: String? = null,
    // VRChat ids backing the calendar actions (Add to Calendar / Join event).
    val groupRefId: String? = null,   // grp_...
    val eventRefId: String? = null,   // cal_... (per-OCCURRENCE id; differs across
                                      // a recurring series' occurrences)
    // Stable per-SERIES id (every occurrence of a repeat shares it). This is the
    // real identity used to group/collapse/match events — the normalized title is
    // only a fallback when VRChat didn't provide one. Keying on the title caused
    // two DISTINCT series to merge, cross-write each other's banner/languages/
    // description, and flip-flop which one a card showed.
    val seriesId: String? = null,     // s...
    // Set true when the event has been confirmed DELETED from VRChat (a 404 on the
    // single-event fetch, with the series also absent from the group calendar). A
    // removed event drops out of "Going", renders with a red "Removed" treatment in
    // the normal area, and is dismissable.
    val removed: Boolean = false,
    // Scheduled timing (epoch ms; 0 = unknown). Drives the countdown / Live
    // now / Ended status chip and the Starts/Ends rows.
    val startsAtMs: Long = 0L,
    val endsAtMs: Long = 0L,
    // When the event/announcement was posted (epoch ms; 0 = unknown).
    val createdAtMs: Long = 0L,
    // Calendar metadata: -1 = unknown count.
    val interestedCount: Int = -1,
    val category: String? = null,     // "Performance" / "Hangout" / ...
    val platforms: String? = null,    // CSV: "standalonewindows,android,ios"
    val accessType: String? = null,   // "public" / "group" / ...
    val languages: String? = null,    // CSV of language codes ("eng,jpn")
    // Whether the USER has this event on their VRChat calendar (null = unknown).
    // Updated optimistically when the Add/Remove button succeeds. following==true
    // = a "signed up" event (golden-pinned to the top of the notifications).
    val following: Boolean? = null,
    // Whether this event repeats (part of a recurring series). Best-effort from
    // the calendar object; false when unknown.
    val recurring: Boolean = false,
    // The event's organizer (the user who created it, distinct from the group).
    // Clickable -> their VRChat profile. Name resolved during enrichment.
    val organizerId: String? = null,   // usr_...
    val organizerName: String? = null
)

data class InAppAlertGroup(
    val groupId: String,
    val title: String,
    val url: String? = null,
    val events: List<InAppAlertEvent>,
    val firstSeenMs: Long,
    val lastUpdatedMs: Long,
    // User pinned this group (long-press / pin button). Pinned groups float to a
    // "📌 Pinned" section (below "Signed up Events"), can't be dismissed, and are
    // preserved by Dismiss-all. Manual + universal (any notification type). Capped
    // at MAX_PINNED. Persisted.
    val pinned: Boolean = false
)

/**
 * Optional rich event/announcement metadata threaded through
 * `fireEventNotification` into the [InAppAlertEvent] it creates. Mirrors the
 * rich fields on the event; see those for semantics.
 */
data class AlertRichMeta(
    val imageUrl: String? = null,
    val groupRefId: String? = null,
    val eventRefId: String? = null,
    val seriesId: String? = null,
    val startsAtMs: Long = 0L,
    val endsAtMs: Long = 0L,
    val createdAtMs: Long = 0L,
    val interestedCount: Int = -1,
    val category: String? = null,
    val platforms: String? = null,
    val accessType: String? = null,
    val languages: String? = null,
    val following: Boolean? = null,
    val recurring: Boolean = false,
    val organizerId: String? = null,
    val organizerName: String? = null
)

// Kept for backward compat with callers that don't use grouping
data class InAppAlert(
    val id: String,
    val title: String,
    val body: String,
    val url: String?,
    val timestampMs: Long,
    val beforeText: String? = null,
    val afterText: String? = null
)

object InAppAlertState {
    private const val PREFS_NAME = "vrca_in_app_alerts"
    private const val KEY_GROUPS = "groups_json"
    // Group count is unbounded (notifications are "infinite") — the UI lazy-renders
    // them so a long history doesn't lag, and a "Dismiss all" button clears them.
    private const val MAX_EVENTS_PER_GROUP = 50

    private val _groups = MutableStateFlow<List<InAppAlertGroup>>(emptyList())
    val groups: StateFlow<List<InAppAlertGroup>> = _groups

    fun load(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_GROUPS, null)
        if (raw != null) {
            _groups.value = deserializeGroups(raw)
            // Startup sweep: drop stored images orphaned by dismissals/prunes that
            // happened without a gc (e.g. process death mid-dismiss).
            AlertImageStore.gc(ctx)
            return
        }
        // Migration: load old flat alerts into single-event groups
        val oldRaw = prefs.getString("alerts_json", "[]") ?: "[]"
        val arr = try { JSONArray(oldRaw) } catch (_: Throwable) { JSONArray() }
        val migrated = mutableListOf<InAppAlertGroup>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val ts = obj.optLong("ts")
            val event = InAppAlertEvent(
                id = obj.optString("id"),
                body = obj.optString("body"),
                beforeText = obj.optString("before").ifBlank { null },
                afterText = obj.optString("after").ifBlank { null },
                timestampMs = ts
            )
            migrated += InAppAlertGroup(
                groupId = obj.optString("id"),
                title = obj.optString("title"),
                url = obj.optString("url").ifBlank { null },
                events = listOf(event),
                firstSeenMs = ts,
                lastUpdatedMs = ts
            )
        }
        _groups.value = migrated
        if (migrated.isNotEmpty()) persist(ctx)
    }

    /**
     * Adds [event] to the group [groupKey]. When [singleEvent] is true the group
     * keeps ONLY the latest event (the new one replaces whatever was there) instead
     * of accumulating a history — used for invite requests, where you only need to
     * see that a person is asking for an invite, not every individual request.
     */
    fun addGroupedEvent(
        ctx: Context,
        groupKey: String,
        title: String,
        url: String?,
        event: InAppAlertEvent,
        singleEvent: Boolean = false,
        dedupByActionData: Boolean = false
    ) {
        val current = _groups.value.toMutableList()
        val idx = current.indexOfFirst { it.groupId == groupKey }
        if (idx >= 0) {
            val existing = current[idx]
            val updatedEvents = if (singleEvent) {
                mutableListOf(event)
            } else {
                existing.events.toMutableList().also {
                    // Collapse repeat events that point at the SAME target (e.g. the
                    // same person re-inviting to the SAME instance 3x) into one entry
                    // — drop the older duplicate, keep this fresher one. Different
                    // instances (different actionData) stay as separate entries.
                    if (dedupByActionData && !event.actionData.isNullOrBlank()) {
                        it.removeAll { e -> e.actionData == event.actionData }
                    }
                    it.add(event)
                    while (it.size > MAX_EVENTS_PER_GROUP) it.removeFirst()
                }
            }
            current[idx] = existing.copy(
                events = updatedEvents,
                lastUpdatedMs = event.timestampMs
            )
            // Move to front so most-recently-updated groups appear first
            val updated = current.removeAt(idx)
            current.add(0, updated)
        } else {
            current.add(0, InAppAlertGroup(
                groupId = groupKey,
                title = title,
                url = url,
                events = listOf(event),
                firstSeenMs = event.timestampMs,
                lastUpdatedMs = event.timestampMs
            ))
        }
        _groups.value = current
        persist(ctx)
    }

    // Backward-compatible: adds a single-event group (for non-grouped alerts)
    fun addAlert(ctx: Context, alert: InAppAlert) {
        addGroupedEvent(
            ctx = ctx,
            groupKey = alert.id,
            title = alert.title,
            url = alert.url,
            event = InAppAlertEvent(
                id = alert.id,
                body = alert.body,
                beforeText = alert.beforeText,
                afterText = alert.afterText,
                timestampMs = alert.timestampMs
            )
        )
    }

    /**
     * Removes ONE event from a group (the per-event X on a multi-event card). If
     * that empties the group, the whole group is removed. For a recurring series
     * (collapsed to one visible entry) this removes ALL occurrences of that title,
     * so dismissing the shown card actually clears the series rather than surfacing
     * the next occurrence.
     */
    fun dismissEvent(ctx: Context, groupId: String, eventId: String) {
        val cur = _groups.value.toMutableList()
        val idx = cur.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return
        val g = cur[idx]
        val target = g.events.firstOrNull { it.id == eventId } ?: return
        val remaining = if (target.recurring && !target.eventTitle.isNullOrBlank()) {
            val t = target.eventTitle.trim().lowercase()
            g.events.filter { !(it.recurring && it.eventTitle?.trim()?.lowercase() == t) }
        } else {
            g.events.filter { it.id != eventId }
        }
        if (remaining.isEmpty()) cur.removeAt(idx)
        else cur[idx] = g.copy(events = remaining)
        _groups.value = cur
        persist(ctx)
        AlertImageStore.gc(ctx)
    }

    /** Removes a SUBSET of events from a group (used when a group is split across
     *  sections — dismissing the "normal" remainder must not delete the followed
     *  events shown in the signed-up section). Empties → removes the group. */
    fun dismissEvents(ctx: Context, groupId: String, eventIds: Collection<String>) {
        val ids = eventIds.toSet()
        if (ids.isEmpty()) return
        val cur = _groups.value.toMutableList()
        val idx = cur.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return
        val g = cur[idx]
        val remaining = g.events.filter { it.id !in ids }
        if (remaining.isEmpty()) cur.removeAt(idx) else cur[idx] = g.copy(events = remaining)
        _groups.value = cur
        persist(ctx)
        AlertImageStore.gc(ctx)
    }

    fun dismiss(ctx: Context, groupId: String) {
        val current = _groups.value.toMutableList()
        current.removeAll { it.groupId == groupId }
        _groups.value = current
        persist(ctx)
        // Drop stored banner/thumbnail files nothing references anymore.
        AlertImageStore.gc(ctx)
    }

    // An event is live for up to 4h after start when it has no published end
    // time (matches the UI's eventPhase()).
    private const val LIVE_TAIL_MS = 4L * 60 * 60 * 1000

    /** True once an event has clearly ENDED (past its end, or >4h past start with
     *  no end). Unknown-timing events (no start AND no end) are never "ended". */
    fun eventEnded(e: InAppAlertEvent, nowMs: Long): Boolean = when {
        e.endsAtMs > 0L -> e.endsAtMs < nowMs
        e.startsAtMs > 0L -> nowMs - e.startsAtMs > LIVE_TAIL_MS
        else -> false
    }

    /**
     * Removes events that have clearly ENDED from every group so concluded events
     * stop cluttering "Going" / the notifications area (an event you were going to
     * shouldn't sit in "Going" days after it finished). DELETED cards (`removed`)
     * are KEPT — they show the red "Removed" state until the user dismisses them —
     * and non-event alerts (no timing) are untouched. A recurring series keeps its
     * upcoming occurrences and only sheds the past ones. Emptied groups are dropped.
     * Returns true if anything changed.
     */
    fun pruneEndedEvents(ctx: Context, nowMs: Long): Boolean {
        var changed = false
        val next = _groups.value.mapNotNull { g ->
            val kept = g.events.filter { it.removed || !eventEnded(it, nowMs) }
            when {
                kept.size == g.events.size -> g
                kept.isEmpty() -> { changed = true; null }
                else -> { changed = true; g.copy(events = kept) }
            }
        }
        if (!changed) return false
        _groups.value = next
        persist(ctx)
        AlertImageStore.gc(ctx)
        return true
    }

    // Manual-pin cap (signed-up/calendar events are separate and uncapped).
    const val MAX_PINNED = 10

    // SHORT-WINDOW per-SERIES follow override, keyed by [followSeriesKey]. When the
    // user taps Add/Remove-from-Calendar the local decision wins over the server read
    // for [FOLLOW_OVERRIDE_MS] only — just long enough to cover VRChat's follow
    // propagation delay so an in-flight enrich can't flip a just-toggled event back.
    // After the window the SERVER's `userInterest.isFollowing` is authoritative, so a
    // follow/unfollow made on the WEBSITE or in the VRCHAT CLIENT is picked up by the
    // 10s expanded poll / 60s tab loop. (It is NOT permanent: that permanence was a
    // workaround for the old extractEventFollowing bug — which reported every event
    // as followed — and permanence blocked external follow changes from ever showing.
    // With the follow field now read correctly there is no flip-back to guard against
    // beyond the propagation window, and the override is deliberately per SERIES so a
    // recurring event's occurrences toggle together.)
    private val followOverrides = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()
    private const val FOLLOW_OVERRIDE_MS = 30_000L

    /** Stable per-SERIES key: the normalized title (recurring occurrences share it),
     *  or id:<evId> when there's no title. Used by the toggle store, the enricher,
     *  and the display split so all three agree on what "this event" is. */
    fun followSeriesKey(title: String?, evId: String?): String {
        val t = title?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()
        return if (t.isNotBlank()) "t:$t" else "id:${evId.orEmpty()}"
    }

    /** Records the user's in-app follow decision for a series (short-window override). */
    fun recordFollowToggle(ctx: Context, key: String, target: Boolean) {
        if (key.isBlank() || key == "id:") return
        followOverrides[key] = target to System.currentTimeMillis()
    }

    /** The in-app follow override for [key] if still within the propagation window,
     *  else null (server value is then authoritative → external changes flow through). */
    fun followOverride(key: String): Boolean? {
        val (target, ts) = followOverrides[key] ?: return null
        return if (System.currentTimeMillis() - ts < FOLLOW_OVERRIDE_MS) target
        else { followOverrides.remove(key); null }
    }

    /** Applies a follow decision to EVERY event in [groupKey] sharing this series
     *  (matched by normalized title, or the single event by id when titleless) so the
     *  optimistic UI update covers all of a recurring series' occurrences at once. */
    fun applyFollowToSeries(ctx: Context, groupKey: String, title: String?, evId: String?, following: Boolean) {
        val t = title?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()
        enrichEvents(
            ctx, groupKey,
            match = { e ->
                if (t.isNotBlank()) (e.eventTitle?.trim()?.lowercase()?.replace(Regex("\\s+"), " ") ?: "") == t
                else e.eventRefId == evId || e.id == evId
            },
            transform = { e ->
                // Optimistically move the interested count with the user's OWN
                // follow decision (mirrors the website's instant ±1) so
                // "Remove from Calendar" visibly drops the count right away instead
                // of waiting for the next enrich to read the server's authoritative
                // interestedUserCount (which lags, or never runs if the card
                // collapses immediately after). Only adjust on a genuine state
                // change and only when we have a real count; the enrich reconciles
                // any drift back to the server value.
                val wasFollowing = e.following == true
                val delta = when {
                    following && !wasFollowing -> 1
                    !following && wasFollowing -> -1
                    else -> 0
                }
                val newCount = if (e.interestedCount >= 0)
                    (e.interestedCount + delta).coerceAtLeast(0) else e.interestedCount
                e.copy(following = following, interestedCount = newCount)
            }
        )
    }

    /** The existing display title for [groupKey], or null if the group isn't present. */
    fun groupTitleOf(groupKey: String): String? =
        _groups.value.firstOrNull { it.groupId == groupKey }?.title

    fun pinnedCount(): Int = _groups.value.count { it.pinned }

    /**
     * Toggle a group's manual pin. Returns false (no-op) when trying to pin past
     * [MAX_PINNED] — the caller shows a "pinned is full" toast. Pinning/unpinning
     * is universal (any alert type) and persisted.
     */
    fun setPinned(ctx: Context, groupId: String, pinned: Boolean): Boolean {
        val cur = _groups.value.toMutableList()
        val idx = cur.indexOfFirst { it.groupId == groupId }
        if (idx < 0) return false
        if (cur[idx].pinned == pinned) return true
        if (pinned && pinnedCount() >= MAX_PINNED) return false
        cur[idx] = cur[idx].copy(pinned = pinned)
        _groups.value = cur
        persist(ctx)
        return true
    }

    /** Clears in-app alert groups (the "Dismiss all" action) EXCEPT signed-up
     *  events (following==true) AND manually-pinned groups — both are kept on
     *  purpose (their per-card X is hidden too). Returns the cleared group ids so
     *  the caller can cancel their linked Android notifications. */
    fun dismissAll(ctx: Context): List<String> {
        fun keep(g: InAppAlertGroup) = g.pinned || g.events.any { it.following == true }
        val kept = _groups.value.filter { keep(it) }
        val cleared = _groups.value.filter { !keep(it) }
        _groups.value = kept
        persist(ctx)
        AlertImageStore.gc(ctx)
        return cleared.map { it.groupId }
    }

    /**
     * Applies [transform] to every event in [groupKey] matched by [match] and
     * persists. Used to ENRICH an already-fired alert in place — e.g. a thin
     * notification-v2 event upgraded with the banner/timing/interested-count from
     * the targeted calendar sweep, an Add-to-Calendar button updating `following`,
     * or an invite target learning its world image. Returns true if anything
     * changed. Never fires a notification — display-state only.
     */
    fun enrichEvents(
        ctx: Context,
        groupKey: String,
        match: (InAppAlertEvent) -> Boolean,
        transform: (InAppAlertEvent) -> InAppAlertEvent
    ): Boolean {
        val current = _groups.value.toMutableList()
        val idx = current.indexOfFirst { it.groupId == groupKey }
        if (idx < 0) return false
        val g = current[idx]
        var changed = false
        val updated = g.events.map { e ->
            if (match(e)) {
                val t = transform(e)
                if (t != e) { changed = true; t } else e
            } else e
        }
        if (!changed) return false
        current[idx] = g.copy(events = updated)
        _groups.value = current
        persist(ctx)
        return true
    }

    /** Normalized event title for cross-path matching (same as the enricher). */
    private fun normEventTitle(t: String?): String =
        t?.trim()?.lowercase()?.replace(Regex("\\s+"), " ").orEmpty()

    /**
     * True if [groupKey] already holds an alert for this event — matched by the
     * exact `cal_` [eventId], OR by its normalized event title. Used by BOTH
     * event fire paths (the live notification-v2 push AND the REST calendar
     * sweep) to avoid firing a DUPLICATE of the same event.
     *
     * A freshly-created event's v2 push frequently can't resolve the `cal_` id
     * (thin payload), so its thin card and the REST sweep's full card would
     * otherwise coexist — one in the normal area, one in "Signed up". Matching
     * on the title lets whichever fires SECOND dedup against the first even when
     * only one of them knew the id. (Tradeoff: two genuinely-distinct undismissed
     * events in the same group sharing an EXACT title collapse to one — rare, and
     * it aligns with the recurring-series "one card per series" behavior.)
     */
    fun hasEventFor(groupKey: String, eventId: String, normalizedTitle: String): Boolean {
        val g = _groups.value.firstOrNull { it.groupId == groupKey } ?: return false
        return g.events.any { e ->
            (eventId.isNotBlank() && e.eventRefId == eventId) ||
                (normalizedTitle.isNotBlank() && normEventTitle(e.eventTitle) == normalizedTitle)
        }
    }

    /**
     * SERIES-aware identity test used by the enricher: does [groupKey] already hold
     * a card for this event? Matches by (in strength order) the stable [seriesId],
     * the per-occurrence [eventId], or the normalized title. This is what lets the
     * enricher know whether to UPDATE an existing card or RE-ADD a dismissed one.
     */
    fun hasSeriesCard(groupKey: String, seriesId: String?, eventId: String?, normalizedTitle: String): Boolean {
        val g = _groups.value.firstOrNull { it.groupId == groupKey } ?: return false
        return g.events.any { e -> eventMatchesSeries(e, seriesId, eventId, normalizedTitle) }
    }

    /** Shared series-identity predicate. seriesId (when both sides have it) is the
     *  strongest signal; then the occurrence id; then the normalized title. */
    fun eventMatchesSeries(
        e: InAppAlertEvent,
        seriesId: String?,
        eventId: String?,
        normalizedTitle: String
    ): Boolean {
        if (!seriesId.isNullOrBlank() && e.seriesId == seriesId) return true
        if (!eventId.isNullOrBlank() && e.eventRefId == eventId) return true
        return normalizedTitle.isNotBlank() && normEventTitle(e.eventTitle) == normalizedTitle
    }

    /**
     * Re-adds [event] to [groupKey] WITHOUT firing an Android notification — the
     * "re-surface a swiped followed event" path (Fix for events subscribed to /
     * created then dismissed from the in-app list vanishing from "Going"). Only
     * adds when no card for this series exists (so it can't duplicate a live one);
     * returns true if it added. The server follow-read that drives this already
     * detects website/PC/headset subscribes, so this simply makes that read able to
     * re-materialize a card the user swiped away.
     */
    fun readdFollowedEventIfMissing(ctx: Context, groupKey: String, groupTitle: String, event: InAppAlertEvent): Boolean {
        val normTitle = normEventTitle(event.eventTitle)
        if (hasSeriesCard(groupKey, event.seriesId, event.eventRefId, normTitle)) return false
        addGroupedEvent(ctx, groupKey, groupTitle, event.url, event)
        return true
    }

    /**
     * Marks every card of a series in [groupKey] as REMOVED (event deleted from
     * VRChat) and clears its `following` so it drops out of "Going" and renders with
     * the red "Removed" treatment in the normal area. Returns true if anything
     * changed. Never fires a notification.
     */
    fun markSeriesRemoved(
        ctx: Context,
        groupKey: String,
        seriesId: String?,
        eventId: String?,
        normalizedTitle: String
    ): Boolean = enrichEvents(
        ctx, groupKey,
        match = { e -> !e.removed && eventMatchesSeries(e, seriesId, eventId, normalizedTitle) },
        transform = { e -> e.copy(removed = true, following = false) }
    )

    /** Records the world image for invite-target events whose actionData is
     *  [location] (any group) — lets the image store keep the file referenced
     *  until the invite alert is dismissed. */
    fun setInviteTargetImage(ctx: Context, location: String, imageUrl: String) {
        if (location.isBlank() || imageUrl.isBlank()) return
        var changed = false
        val current = _groups.value.map { g ->
            val updated = g.events.map { e ->
                if (e.actionData == location && e.imageUrl != imageUrl) {
                    changed = true; e.copy(imageUrl = imageUrl)
                } else e
            }
            if (updated != g.events) g.copy(events = updated) else g
        }
        if (changed) {
            _groups.value = current
            persist(ctx)
        }
    }

    private fun persist(ctx: Context) {
        val arr = JSONArray()
        for (g in _groups.value) {
            val eventsArr = JSONArray()
            for (e in g.events) {
                eventsArr.put(JSONObject().apply {
                    put("id", e.id)
                    put("body", e.body)
                    put("before", e.beforeText ?: "")
                    put("after", e.afterText ?: "")
                    put("ts", e.timestampMs)
                    put("eventTitle", e.eventTitle ?: "")
                    put("url", e.url ?: "")
                    put("actionType", e.actionType ?: "")
                    put("actionData", e.actionData ?: "")
                    put("imageUrl", e.imageUrl ?: "")
                    put("groupRefId", e.groupRefId ?: "")
                    put("eventRefId", e.eventRefId ?: "")
                    put("seriesId", e.seriesId ?: "")
                    put("removed", e.removed)
                    put("startsAt", e.startsAtMs)
                    put("endsAt", e.endsAtMs)
                    put("createdAt", e.createdAtMs)
                    put("interested", e.interestedCount)
                    put("category", e.category ?: "")
                    put("platforms", e.platforms ?: "")
                    put("accessType", e.accessType ?: "")
                    put("languages", e.languages ?: "")
                    // -1 unknown / 0 false / 1 true
                    put("following", when (e.following) { null -> -1; false -> 0; true -> 1 })
                    put("recurring", e.recurring)
                    put("organizerId", e.organizerId ?: "")
                    put("organizerName", e.organizerName ?: "")
                })
            }
            arr.put(JSONObject().apply {
                put("groupId", g.groupId)
                put("title", g.title)
                put("url", g.url ?: "")
                put("events", eventsArr)
                put("firstSeen", g.firstSeenMs)
                put("lastUpdated", g.lastUpdatedMs)
                put("pinned", g.pinned)
            })
        }
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_GROUPS, arr.toString()).apply()
    }

    private fun deserializeGroups(raw: String): List<InAppAlertGroup> {
        val arr = try { JSONArray(raw) } catch (_: Throwable) { return emptyList() }
        val list = mutableListOf<InAppAlertGroup>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val eventsArr = obj.optJSONArray("events") ?: continue
            val events = mutableListOf<InAppAlertEvent>()
            for (j in 0 until eventsArr.length()) {
                val eObj = eventsArr.optJSONObject(j) ?: continue
                events += InAppAlertEvent(
                    id = eObj.optString("id"),
                    body = eObj.optString("body"),
                    beforeText = eObj.optString("before").ifBlank { null },
                    afterText = eObj.optString("after").ifBlank { null },
                    timestampMs = eObj.optLong("ts"),
                    eventTitle = eObj.optString("eventTitle").ifBlank { null },
                    url = eObj.optString("url").ifBlank { null },
                    actionType = eObj.optString("actionType").ifBlank { null },
                    actionData = eObj.optString("actionData").ifBlank { null },
                    imageUrl = eObj.optString("imageUrl").ifBlank { null },
                    groupRefId = eObj.optString("groupRefId").ifBlank { null },
                    eventRefId = eObj.optString("eventRefId").ifBlank { null },
                    seriesId = eObj.optString("seriesId").ifBlank { null },
                    removed = eObj.optBoolean("removed", false),
                    startsAtMs = eObj.optLong("startsAt", 0L),
                    endsAtMs = eObj.optLong("endsAt", 0L),
                    createdAtMs = eObj.optLong("createdAt", 0L),
                    interestedCount = eObj.optInt("interested", -1),
                    category = eObj.optString("category").ifBlank { null },
                    platforms = eObj.optString("platforms").ifBlank { null },
                    accessType = eObj.optString("accessType").ifBlank { null },
                    languages = eObj.optString("languages").ifBlank { null },
                    following = when (eObj.optInt("following", -1)) {
                        1 -> true; 0 -> false; else -> null
                    },
                    recurring = eObj.optBoolean("recurring", false),
                    organizerId = eObj.optString("organizerId").ifBlank { null },
                    organizerName = eObj.optString("organizerName").ifBlank { null }
                )
            }
            list += InAppAlertGroup(
                groupId = obj.optString("groupId"),
                title = obj.optString("title"),
                url = obj.optString("url").ifBlank { null },
                events = events,
                firstSeenMs = obj.optLong("firstSeen"),
                lastUpdatedMs = obj.optLong("lastUpdated"),
                pinned = obj.optBoolean("pinned", false)
            )
        }
        return list
    }
}
