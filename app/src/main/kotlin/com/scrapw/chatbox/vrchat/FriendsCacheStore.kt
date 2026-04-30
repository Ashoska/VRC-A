package com.scrapw.chatbox.vrchat

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Local-only persistence for the VRChat friends cache.
 *
 * Stores `Map<userId, displayName>` in SharedPreferences as a JSON object.
 * Used by [VrchatPipelineService] to detect unfriends across sessions
 * without round-tripping to Firestore — friends data is only needed by the
 * user's own app for unfriend notifications, never by admins, so there's
 * no reason to push it to the server.
 */
object FriendsCacheStore {
    private const val TAG = "FriendsCacheStore"
    private const val PREFS_FILE = "vrca_friends_cache"
    private const val KEY_FRIENDS_JSON = "friends_json"

    fun load(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_FRIENDS_JSON, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { id ->
                    val name = obj.optString(id, "")
                    if (id.isNotBlank() && name.isNotBlank()) put(id, name)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse cached friends, ignoring", e)
            emptyMap()
        }
    }

    fun save(context: Context, friends: Map<String, String>) {
        val obj = JSONObject()
        friends.forEach { (id, name) -> obj.put(id, name) }
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_FRIENDS_JSON, obj.toString())
            .apply()
    }
}
