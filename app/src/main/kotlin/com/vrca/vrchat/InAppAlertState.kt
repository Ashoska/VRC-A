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
    val eventRefId: String? = null,   // cal_...
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
    val lastUpdatedMs: Long
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

    fun dismiss(ctx: Context, groupId: String) {
        val current = _groups.value.toMutableList()
        current.removeAll { it.groupId == groupId }
        _groups.value = current
        persist(ctx)
        // Drop stored banner/thumbnail files nothing references anymore.
        AlertImageStore.gc(ctx)
    }

    /** Clears every in-app alert group (the "Dismiss all" action). Returns the
     *  group ids that were cleared so the caller can cancel their linked Android
     *  notifications too. */
    fun dismissAll(ctx: Context): List<String> {
        val ids = _groups.value.map { it.groupId }
        _groups.value = emptyList()
        persist(ctx)
        AlertImageStore.gc(ctx)
        return ids
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
                lastUpdatedMs = obj.optLong("lastUpdated")
            )
        }
        return list
    }
}
