package com.vrca.vrchat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Avatar-database search via the public **avtrdb** API (the same source VRC-NEXUS
 * uses). Given the `avtr_`/`usr_` ids the instance-roster log-scan surfaces — or a
 * free-text query — this resolves worn-avatar name + author + thumbnail + which
 * platforms it's built for. No auth (public endpoint).
 */
object AvatarSearch {

    private const val TAG = "AvatarSearch"
    private const val BASE = "https://api.avtrdb.com/v2/avatar/search"

    data class Result(
        val id: String,
        val name: String,
        val author: String,
        val imageUrl: String,
        /** "PC"/"Quest"/"iOS" from the avatar's unity packages. */
        val platforms: List<String>
    )

    suspend fun search(query: String): List<Result> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val url = "$BASE?query=${URLEncoder.encode(query.trim(), "UTF-8")}&page=0"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VRC-A/1.0 (VRChat companion)")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15_000; readTimeout = 15_000
            }
            if (conn.responseCode != 200) {
                Log.w(TAG, "avtrdb search http ${conn.responseCode}")
                return@withContext emptyList()
            }
            val body = conn.inputStream.bufferedReader().readText()
            val root = JSONObject(body)
            val arr = root.optJSONArray("avatars")
                ?: root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = a.optString("id", "").ifBlank { a.optString("avatar_id", "") }
                if (id.isBlank()) return@mapNotNull null
                val platforms = a.optJSONArray("unityPackages")?.let { up ->
                    (0 until up.length()).mapNotNull {
                        up.optJSONObject(it)?.optString("platform", "")?.takeIf { p -> p.isNotBlank() }
                    }.map { VrchatAuthManager.prettyPlatform(it) }.filter { it.isNotBlank() }.distinct()
                } ?: emptyList()
                Result(
                    id = id,
                    name = a.optString("name", ""),
                    author = a.optString("authorName", "").ifBlank { a.optString("author_name", "") },
                    imageUrl = a.optString("thumbnailImageUrl", "").ifBlank { a.optString("imageUrl", "") },
                    platforms = platforms
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "avtrdb search failed", e); emptyList()
        }
    }
}
