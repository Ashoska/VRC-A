package com.scrapw.chatbox.vrchat

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Local-only persistence for the VRChat friends cache.
 *
 * Stores `Map<userId, FriendCacheEntry>` in SharedPreferences as a JSON
 * object. Used by [VrchatPipelineService] to detect unfriends, status
 * changes, avatar changes etc. across sessions without round-tripping to
 * Firestore — friends data is only needed by the user's own app for
 * notifications, never by admins, so there's no reason to push it server-side.
 *
 * Backwards-compatible with the legacy `Map<userId, displayName>` JSON
 * format: a string value is treated as a name-only entry.
 */
data class FriendCacheEntry(
    val displayName: String,
    val status: String = "",
    val statusDescription: String = "",
    val location: String = "",
    val worldName: String = "",
    val avatarThumb: String = "",
    val bio: String = ""
)

object FriendsCacheStore {
    private const val TAG = "FriendsCacheStore"
    private const val PREFS_FILE = "vrca_friends_cache"
    private const val KEY_FRIENDS_JSON = "friends_json"

    fun load(context: Context): Map<String, FriendCacheEntry> {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FRIENDS_JSON, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { id ->
                    if (id.isBlank()) return@forEach
                    val v = obj.opt(id) ?: return@forEach
                    val entry = when (v) {
                        is JSONObject -> FriendCacheEntry(
                            displayName       = v.optString("displayName", ""),
                            status            = v.optString("status", ""),
                            statusDescription = v.optString("statusDescription", ""),
                            location          = v.optString("location", ""),
                            worldName         = v.optString("worldName", ""),
                            avatarThumb       = v.optString("avatarThumb", ""),
                            bio               = v.optString("bio", "")
                        )
                        is String -> FriendCacheEntry(displayName = v)
                        else -> null
                    } ?: return@forEach
                    if (entry.displayName.isNotBlank()) put(id, entry)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse cached friends, ignoring", e)
            emptyMap()
        }
    }

    fun save(context: Context, friends: Map<String, FriendCacheEntry>) {
        val obj = JSONObject()
        friends.forEach { (id, entry) ->
            obj.put(id, JSONObject().apply {
                put("displayName", entry.displayName)
                if (entry.status.isNotBlank())            put("status", entry.status)
                if (entry.statusDescription.isNotBlank()) put("statusDescription", entry.statusDescription)
                if (entry.location.isNotBlank())          put("location", entry.location)
                if (entry.worldName.isNotBlank())         put("worldName", entry.worldName)
                if (entry.avatarThumb.isNotBlank())       put("avatarThumb", entry.avatarThumb)
                if (entry.bio.isNotBlank())               put("bio", entry.bio)
            })
        }
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FRIENDS_JSON, obj.toString())
            .apply()
    }
}
