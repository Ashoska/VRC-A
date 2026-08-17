package com.vrca.vrchat

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
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
                // Real avtrdb schema: id = "vrc_id"; author is an OBJECT {name,vrc_id};
                // thumbnail = "image_url"; platforms = "compatibility" (["pc","android",
                // "ios"]). (The old code guessed id/authorName/thumbnailImageUrl/
                // unityPackages — all absent — so every result was dropped.)
                val id = a.optString("vrc_id", "").ifBlank { a.optString("id", "") }
                if (id.isBlank()) return@mapNotNull null
                val author = a.optJSONObject("author")?.optString("name", "")
                    ?: a.optString("authorName", "")
                val platforms = a.optJSONArray("compatibility")?.let { comp ->
                    (0 until comp.length()).mapNotNull {
                        comp.optString(it, "").takeIf { p -> p.isNotBlank() }
                    }.map { prettyAvtrdbPlatform(it) }.filter { it.isNotBlank() }.distinct()
                } ?: emptyList()
                Result(
                    id = id,
                    name = a.optString("name", ""),
                    author = author,
                    imageUrl = a.optString("image_url", "").ifBlank { a.optString("thumbnailImageUrl", "") },
                    platforms = platforms
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "avtrdb search failed", e); emptyList()
        }
    }

    // ---- multi-DB candidate resolve (for the roster clone button) ------------

    /** A resolve candidate from ANY avatar DB. `imageFileId` is the RAW VRChat
     *  `file_…` id when the DB exposes VRChat's real image url (lets the caller
     *  confirm the match with NO extra VRChat call); null for DBs that proxy their
     *  images (those are confirmed via VRChat's `GET /avatars/{id}`). */
    data class Candidate(
        val id: String,
        val name: String,
        val author: String,
        val imageFileId: String?
    )

    private val FILE_ID = Regex("""file_[0-9a-fA-F-]{36}""")

    /**
     * Query MULTIPLE avatar databases by name and merge candidates (deduped by
     * avtr_ id). More sources = better coverage; a WRONG candidate is harmless
     * because the caller confirms every one by the worn avatar's unique image file
     * id. avtrdb proxies its images (imageFileId null → needs a GET /avatars
     * confirm); the VRCX-style `vrcx_search.php` mirrors return VRChat's RAW image
     * url (imageFileId set → direct confirm, no VRChat call). Each source fails soft.
     */
    suspend fun searchCandidates(query: String): List<Candidate> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        listOf(
            async { avtrdbCandidates(q) },
            async { vrcxCandidates("https://requi.dev/vrcx_search.php?search=$q") },
            async { vrcxCandidates("https://avtr.just-h.party/vrcx_search.php?search=$q") }
        ).awaitAll().flatten().filter { it.id.startsWith("avtr_") }.distinctBy { it.id }
    }

    private suspend fun avtrdbCandidates(encodedQuery: String): List<Candidate> = withContext(Dispatchers.IO) {
        val body = httpGet("$BASE?query=$encodedQuery&page=0") ?: return@withContext emptyList()
        try {
            val arr = JSONObject(body).optJSONArray("avatars") ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = a.optString("vrc_id", "").ifBlank { a.optString("id", "") }
                if (id.isBlank()) return@mapNotNull null
                val author = a.optJSONObject("author")?.optString("name", "") ?: a.optString("authorName", "")
                // avtrdb image is proxied (thumb.avtrdb.com/avtr_…) → no VRChat file id.
                Candidate(id, a.optString("name", ""), author, null)
            }
        } catch (e: Exception) { emptyList() }
    }

    /** VRCX-style `vrcx_search.php` sources: a JSON array (or {results:[…]}) whose
     *  items carry VRChat's RAW imageUrl/thumbnailImageUrl. Tolerant to key names. */
    private suspend fun vrcxCandidates(url: String): List<Candidate> = withContext(Dispatchers.IO) {
        val body = httpGet(url) ?: return@withContext emptyList()
        try {
            val arr: JSONArray = if (body.trimStart().startsWith("[")) JSONArray(body)
                else JSONObject(body).let { it.optJSONArray("results") ?: it.optJSONArray("avatars") }
                    ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = a.optString("id", "")
                    .ifBlank { a.optString("avatarId", "") }
                    .ifBlank { a.optString("vrc_id", "") }
                if (!id.startsWith("avtr_")) return@mapNotNull null
                val author = a.optString("authorName", "")
                    .ifBlank { a.optJSONObject("author")?.optString("name", "") ?: "" }
                val img = a.optString("thumbnailImageUrl", "").ifBlank { a.optString("imageUrl", "") }
                Candidate(id, a.optString("name", ""), author, FILE_ID.find(img)?.value)
            }
        } catch (e: Exception) { emptyList() }
    }

    private fun httpGet(url: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VRC-A/1.0 (VRChat companion)")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 12_000; readTimeout = 12_000
            }
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
    }

    /** avtrdb's `compatibility` values ("pc"/"android"/"ios") -> display labels.
     *  (Distinct from VRChat's `standalonewindows`/`android` platform strings.) */
    private fun prettyAvtrdbPlatform(raw: String): String = when (raw.trim().lowercase()) {
        "pc", "standalonewindows", "windows" -> "PC"
        "android", "quest" -> "Quest"
        "ios" -> "iOS"
        else -> ""
    }
}
