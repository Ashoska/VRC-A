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

    /** Whether the USER has this event on their calendar, when the object says. */
    fun extractEventFollowing(ev: JSONObject): Boolean? = when {
        ev.has("isFollowing") -> ev.optBoolean("isFollowing")
        ev.has("userInterest") -> !ev.isNull("userInterest")
        else -> null
    }

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

    /**
     * Sweeps [groupId]'s calendar events + posts and merges fresh values into the
     * matching alert events. Debounced per group ([minIntervalMs]); returns true
     * when a sweep actually ran.
     */
    suspend fun enrich(ctx: Context, groupId: String, minIntervalMs: Long = 60_000L): Boolean {
        if (groupId.isBlank()) return false
        val now = System.currentTimeMillis()
        val last = lastSweepMs[groupId] ?: 0L
        if (now - last < minIntervalMs) return false
        lastSweepMs[groupId] = now
        try {
            // ---- Calendar events: match alerts by the cal_ id ----
            val events = VrchatAuthManager.fetchGroupCalendarEvents(ctx, groupId, 20)
            if (events != null) {
                for (j in 0 until events.length()) {
                    val ev = events.optJSONObject(j) ?: continue
                    val evId = ev.optString("id", "").ifBlank { findIdWithPrefix(ev, "cal_").orEmpty() }
                    if (evId.isBlank()) continue
                    val startsMs = parseTimestampMs(ev.optString("startsAt", ""))
                    val endsMs = parseTimestampMs(ev.optString("endsAt", ""))
                    val createdMs = parseTimestampMs(ev.optString("createdAt", ""))
                    val img = extractEventImageUrl(ev)
                    val category = ev.optString("category", "")
                    val platformsCsv = jsonArrayToCsv(ev.optJSONArray("platforms"))
                    val languagesCsv = jsonArrayToCsv(ev.optJSONArray("languages"))
                    val access = ev.optString("accessType", "")
                    val interested = extractInterestedCount(ev)
                    val following = extractEventFollowing(ev)
                    val title = ev.optString("title", "").ifBlank { ev.optString("name", "") }
                    val desc = ev.optString("description", "").ifBlank { ev.optString("text", "") }
                    if (img != null) AlertImageStore.ensureCached(ctx, img)
                    InAppAlertState.enrichEvents(
                        ctx, "event_$groupId",
                        match = { e ->
                            e.eventRefId == evId ||
                                (e.eventRefId == null && e.url?.contains(evId) == true)
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
