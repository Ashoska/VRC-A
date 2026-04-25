package com.scrapw.chatbox.vrchat

import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Resolves external image URLs (e.g. VRChat world thumbnails) to Discord
 * media-proxy references that can be used in Activity `assets.large_image`.
 *
 * Calls Discord's `POST /applications/{app_id}/external-assets` endpoint
 * with a user token. Caches results in-memory only (bounded LruCache) so
 * the cache is rebuilt as needed across app restarts — no persistent
 * storage to keep the footprint small.
 */
class DiscordExternalAssetResolver(
    private val applicationId: String,
    private val tokenProvider: () -> String,
) {
    companion object {
        private const val TAG = "DiscordRPC.Resolver"
        private const val ENDPOINT = "https://discord.com/api/v10/applications"
        private const val CACHE_SIZE = 64
        private const val NEGATIVE_TTL_MS = 60_000L
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val cache = LruCache<String, String>(CACHE_SIZE)
    private val negativeCache = LruCache<String, Long>(CACHE_SIZE)
    private val mutex = Mutex()
    private val inFlight = HashSet<String>()

    /** Returns a cached `mp:external/...` reference if present, else null. */
    fun peekCached(url: String): String? {
        if (url.isBlank()) return null
        return cache.get(url)
    }

    /** Seeds the in-memory cache from an external persistent store. */
    fun prePopulate(url: String, ref: String) {
        if (url.isBlank() || ref.isBlank()) return
        cache.put(url, ref)
    }

    /**
     * Resolves the URL via Discord's external-assets endpoint.
     * Returns the `mp:external/...` reference, or null on failure.
     * Result is cached in memory so subsequent calls are instant.
     */
    suspend fun resolve(url: String): String? {
        if (url.isBlank()) return null
        cache.get(url)?.let { return it }

        // Skip URLs that recently failed to avoid hammering the API.
        negativeCache.get(url)?.let { failedAt ->
            if (System.currentTimeMillis() - failedAt < NEGATIVE_TTL_MS) return null
            negativeCache.remove(url)
        }

        // Dedupe concurrent resolutions of the same URL.
        mutex.withLock {
            if (url in inFlight) return null
            inFlight.add(url)
        }

        return try {
            val token = tokenProvider().trim()
            if (token.isBlank()) {
                Log.w(TAG, "No Discord token, skipping resolve")
                return null
            }
            val ref = postExternalAssets(url, token)
            if (ref != null) {
                cache.put(url, ref)
            } else {
                negativeCache.put(url, System.currentTimeMillis())
            }
            ref
        } catch (e: Exception) {
            Log.w(TAG, "External asset resolve failed for $url", e)
            negativeCache.put(url, System.currentTimeMillis())
            null
        } finally {
            mutex.withLock { inFlight.remove(url) }
        }
    }

    private fun postExternalAssets(url: String, token: String): String? {
        val body = JSONObject()
            .put("urls", JSONArray().put(url))
            .toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$ENDPOINT/$applicationId/external-assets")
            .addHeader("Authorization", token)
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "external-assets HTTP ${resp.code} for $url")
                return null
            }
            val responseBody = resp.body?.string().orEmpty()
            val arr = JSONArray(responseBody)
            if (arr.length() == 0) return null
            val path = arr.getJSONObject(0).optString("external_asset_path", "")
            if (path.isBlank()) return null
            return "mp:$path"
        }
    }
}
