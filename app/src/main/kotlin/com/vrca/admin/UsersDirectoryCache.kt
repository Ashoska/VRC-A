package com.vrca.admin

import android.content.Context
import com.google.firebase.Timestamp
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * Local persistence for the admin Users directory.
 *
 * Opening the directory renders the full last-known user list INSTANTLY from this
 * cache with ZERO Firestore reads. A fresh server pull happens only on manual
 * refresh / +500 (or first-ever load when the cache is empty) — plain tab
 * re-entry costs nothing. Online/offline is recomputed locally from each row's
 * cached `lastActiveAt` (see [isUserOnline]), so cached rows decay to offline
 * naturally without any reads.
 *
 * This replaces the old behaviour where every directory open did a
 * `get(Source.SERVER)` of up to `sharedLiveLimit` docs (≈N reads per open) and
 * only the most-recently-active 500 were ever visible. The whole list is cached
 * (not just a page), so the 500-row display cap is gone too.
 *
 * Timestamps are stored as epoch-millis Longs and rebuilt on load.
 */
internal object UsersDirectoryCache {
    private const val PREFS = "vrca_admin_directory"
    private const val KEY_USERS = "users_json"

    private fun tsMs(ts: Timestamp?): Long = ts?.toDate()?.time ?: 0L
    private fun msTs(ms: Long): Timestamp? = if (ms > 0L) Timestamp(Date(ms)) else null

    fun save(ctx: Context, users: List<UserRow>) {
        val arr = JSONArray()
        for (u in users) {
            arr.put(JSONObject().apply {
                put("docId", u.docId)
                put("authUid", u.authUid)
                put("displayName", u.displayName)
                put("deviceHash", u.deviceHash)
                put("warned", u.warned)
                put("banned", u.banned)
                put("lastActiveAt", tsMs(u.lastActiveAt))
                put("lastSeenAt", tsMs(u.lastSeenAt))
                put("updatedAt", tsMs(u.updatedAt))
                put("vrchatUserId", u.vrchatUserId)
                put("vrchatDisplayName", u.vrchatDisplayName)
                put("vrchatState", u.vrchatState)
                put("vrchatStatus", u.vrchatStatus)
                put("vrchatIsOnline", u.vrchatIsOnline)
                put("vrchatWorld", u.vrchatWorld)
                put("vrchatPlayerCount", u.vrchatPlayerCount)
                put("vrchatCapacity", u.vrchatCapacity)
                put("vrchatPlatform", u.vrchatPlatform)
                put("vrchatLastSyncAt", tsMs(u.vrchatLastSyncAt))
                put("isOnlineInApp", u.isOnlineInApp)
                put("offlineAt", tsMs(u.offlineAt))
                put("killSignal", tsMs(u.killSignal))
                put("versionName", u.versionName)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_USERS, arr.toString()).apply()
    }

    fun load(ctx: Context): List<UserRow> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_USERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UserRow(
                    docId = o.optString("docId"),
                    authUid = o.optString("authUid"),
                    displayName = o.optString("displayName"),
                    deviceHash = o.optString("deviceHash"),
                    warned = o.optBoolean("warned"),
                    banned = o.optBoolean("banned"),
                    lastActiveAt = msTs(o.optLong("lastActiveAt")),
                    lastSeenAt = msTs(o.optLong("lastSeenAt")),
                    updatedAt = msTs(o.optLong("updatedAt")),
                    vrchatUserId = o.optString("vrchatUserId"),
                    vrchatDisplayName = o.optString("vrchatDisplayName"),
                    vrchatState = o.optString("vrchatState"),
                    vrchatStatus = o.optString("vrchatStatus"),
                    vrchatIsOnline = o.optBoolean("vrchatIsOnline"),
                    vrchatWorld = o.optString("vrchatWorld"),
                    vrchatPlayerCount = o.optInt("vrchatPlayerCount"),
                    vrchatCapacity = o.optInt("vrchatCapacity"),
                    vrchatPlatform = o.optString("vrchatPlatform"),
                    vrchatLastSyncAt = msTs(o.optLong("vrchatLastSyncAt")),
                    isOnlineInApp = o.optBoolean("isOnlineInApp"),
                    offlineAt = msTs(o.optLong("offlineAt")),
                    killSignal = msTs(o.optLong("killSignal")),
                    versionName = o.optString("versionName", "")
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
