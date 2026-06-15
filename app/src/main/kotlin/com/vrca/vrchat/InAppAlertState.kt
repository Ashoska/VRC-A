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
    val actionData: String? = null
)

data class InAppAlertGroup(
    val groupId: String,
    val title: String,
    val url: String? = null,
    val events: List<InAppAlertEvent>,
    val firstSeenMs: Long,
    val lastUpdatedMs: Long
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
    private const val MAX_GROUPS = 20
    private const val MAX_EVENTS_PER_GROUP = 50

    private val _groups = MutableStateFlow<List<InAppAlertGroup>>(emptyList())
    val groups: StateFlow<List<InAppAlertGroup>> = _groups

    fun load(ctx: Context) {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_GROUPS, null)
        if (raw != null) {
            _groups.value = deserializeGroups(raw)
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

    fun addGroupedEvent(
        ctx: Context,
        groupKey: String,
        title: String,
        url: String?,
        event: InAppAlertEvent
    ) {
        val current = _groups.value.toMutableList()
        val idx = current.indexOfFirst { it.groupId == groupKey }
        if (idx >= 0) {
            val existing = current[idx]
            val updatedEvents = existing.events.toMutableList()
            updatedEvents.add(event)
            while (updatedEvents.size > MAX_EVENTS_PER_GROUP) updatedEvents.removeFirst()
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
        while (current.size > MAX_GROUPS) current.removeLast()
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
                    actionData = eObj.optString("actionData").ifBlank { null }
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
