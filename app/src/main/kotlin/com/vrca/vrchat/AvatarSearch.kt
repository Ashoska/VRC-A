package com.vrca.vrchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** VRCX-style `vrcx_search.php` mirrors (expose VRChat's RAW image url, so a
     *  candidate carries the worn file id). The community ones (avtr.just-h.party,
     *  requi.dev) are currently DEAD (DNS gone / connection refused), so this is
     *  EMPTY — avtrdb + our own growing catalog are the live sources. Add a working
     *  mirror base url here (e.g. "https://host/vrcx_search.php") to re-enable one. */
    private val VRCX_MIRRORS: List<String> = emptyList()

    data class Result(
        val id: String,
        val name: String,
        val author: String,
        val imageUrl: String,
        /** "PC"/"Quest"/"iOS" from the avatar's unity packages. */
        val platforms: List<String>,
        /** The author's `usr_` id when the source exposes it. */
        val authorId: String = "",
        /** VRChat's raw image `file_…` id when the source exposes it (VRCX mirrors +
         *  our own catalog do; avtrdb proxies its image, so null there). */
        val imageFileId: String? = null,
        /** Which source it came from (for the per-DB debug + ranking). */
        val source: String = ""
    )

    // avtrdb pages ~20 results each. We paginate to EXHAUSTION (no result cap — fetch
    // every page until one comes back short/empty = the last page). AVTRDB_PAGE_GUARD
    // is ONLY a runaway backstop for a misbehaving API that never returns a short page;
    // real avatar-name searches end in a handful of pages.
    private const val AVTRDB_PAGE_GUARD = 1000
    private const val AVTRDB_PAGE_SIZE = 20

    /** Parse one avtrdb page body into display Results. Real avtrdb schema: id =
     *  "vrc_id"; author is an OBJECT {name,vrc_id}; thumbnail = "image_url"; platforms
     *  = "compatibility" (["pc","android","ios"]). */
    private fun parseAvtrdbResults(body: String): List<Result> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("avatars")
            ?: root.optJSONArray("results")
            ?: root.optJSONArray("data")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = a.optString("vrc_id", "").ifBlank { a.optString("id", "") }
            if (id.isBlank()) return@mapNotNull null
            val author = a.optJSONObject("author")?.optString("name", "") ?: a.optString("authorName", "")
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
                platforms = platforms,
                authorId = a.optJSONObject("author")?.optString("vrc_id", "") ?: "",
                imageFileId = null,
                source = "avtrdb"
            )
        }
    }

    suspend fun search(query: String): List<Result> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val out = LinkedHashMap<String, Result>()
        for (page in 0 until AVTRDB_PAGE_GUARD) {
            val body = httpGet("$BASE?query=$q&page=$page") ?: break
            val parsed = try { parseAvtrdbResults(body) } catch (e: Exception) {
                Log.w(TAG, "avtrdb search parse failed", e); emptyList()
            }
            if (parsed.isEmpty()) break
            for (r in parsed) out.putIfAbsent(r.id, r)
            if (parsed.size < AVTRDB_PAGE_SIZE) break   // short page = last page
        }
        out.values.toList()
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
        val imageFileId: String?,
        val authorId: String = ""
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
        (listOf(async { avtrdbCandidates(q) }) +
            VRCX_MIRRORS.map { m -> async { vrcxCandidates("$m?search=$q") } })
            .awaitAll().flatten().filter { it.id.startsWith("avtr_") }.distinctBy { it.id }
    }

    /**
     * NAME-INDEPENDENT resolve: query the VRCX-style mirrors by the worn avatar's
     * IMAGE FILE ID (`file_…`). Those mirrors emulate VRCX, whose avatar search
     * matches image-file-id / image-url text — so this returns the EXACT avatar
     * even when the log's avatar name is renamed, truncated, generic, or collides.
     * The file id is invariant per upload, so a hit here is unambiguous. avtrdb is
     * skipped (its free-text search is name-oriented and proxies images, so a file
     * id wouldn't match). Fails soft (empty) when a mirror doesn't index by file id.
     */
    suspend fun searchCandidatesByImageFileId(fileId: String): List<Candidate> = coroutineScope {
        if (!fileId.startsWith("file_")) return@coroutineScope emptyList()
        if (VRCX_MIRRORS.isEmpty()) return@coroutineScope emptyList()
        val q = URLEncoder.encode(fileId, "UTF-8")
        VRCX_MIRRORS.map { m -> async { vrcxCandidates("$m?search=$q") } }
            .awaitAll().flatten().filter { it.id.startsWith("avtr_") }.distinctBy { it.id }
    }

    private suspend fun avtrdbCandidates(encodedQuery: String): List<Candidate> = withContext(Dispatchers.IO) {
        val out = LinkedHashMap<String, Candidate>()
        for (page in 0 until AVTRDB_PAGE_GUARD) {
            val body = httpGet("$BASE?query=$encodedQuery&page=$page") ?: break
            val parsed = try {
                val arr = JSONObject(body).optJSONArray("avatars") ?: JSONObject(body).optJSONArray("results")
                if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
                    val a = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = a.optString("vrc_id", "").ifBlank { a.optString("id", "") }
                    if (id.isBlank()) return@mapNotNull null
                    val author = a.optJSONObject("author")?.optString("name", "") ?: a.optString("authorName", "")
                    val authorId = a.optJSONObject("author")?.optString("vrc_id", "") ?: ""
                    // avtrdb image is proxied (thumb.avtrdb.com/avtr_…) → no VRChat file id.
                    Candidate(id, a.optString("name", ""), author, null, authorId)
                }
            } catch (e: Exception) { emptyList() }
            if (parsed.isEmpty()) break
            for (c in parsed) out.putIfAbsent(c.id, c)
            if (parsed.size < AVTRDB_PAGE_SIZE) break
        }
        out.values.toList()
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
                val authorId = a.optString("authorId", "").ifBlank { a.optJSONObject("author")?.optString("vrc_id", "") ?: "" }
                Candidate(id, a.optString("name", ""), author, FILE_ID.find(img)?.value, authorId)
            }
        } catch (e: Exception) { emptyList() }
    }

    // ---- unified search (our catalog FIRST, then every DB) -------------------

    /**
     * Search EVERY source at once — our crowdsourced catalog FIRST, then avtrdb +
     * the two VRCX mirrors — merged/deduped by avtr_ id. Any result that carries a
     * VRChat image file id (VRCX mirrors + our catalog) is CONTRIBUTED back into our
     * catalog immediately, so searching slowly fills our DB from the others.
     */
    suspend fun searchAll(context: Context, query: String): List<Result> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val remote = (listOf(async { runCatching { search(query) }.getOrDefault(emptyList()) }) +
            VRCX_MIRRORS.map { m -> async { vrcxResults("$m?search=$q") } })
            .awaitAll().flatten()
        val ours = catalogResults(query)
        // Dedup by a NORMALIZED avatar id (trim + lowercase) so a casing/whitespace
        // difference between sources can't slip a duplicate through. Our catalog wins.
        val merged = LinkedHashMap<String, Result>()
        for (r in ours + remote) {
            val key = r.id.trim().lowercase()
            if (key.startsWith("avtr_")) merged.putIfAbsent(key, r)
        }
        val list = merged.values.toList()
        // Fill our DB from the file-id-bearing results (free — no VRChat call).
        for (r in list) {
            val fid = r.imageFileId
            if (fid != null && r.source != "catalog") {
                AvatarGlobalDb.contribute(context, fid, r.id, r.name, r.author, r.authorId, r.platforms)
            }
        }
        list
    }

    // ---- local-first progressive search --------------------------------------
    // Instead of blocking on avtrdb + the mirrors before showing anything, the UI
    // shows [localResults] INSTANTLY (our in-memory catalog) and then folds in
    // [remoteFill]. Once our catalog covers a query, results are instant and the
    // external sources — whose hits all dedup away — no longer gate the search;
    // they still run (with a per-source timeout) purely to catch avatars we don't
    // have yet and to contribute them back.

    /** How long any single external source may take before it's dropped for this
     *  search (one slow mirror can't stall the whole thing). */
    private const val REMOTE_SOURCE_TIMEOUT_MS = 6_000L

    /** Our crowdsourced catalog as search results — LOCAL, in-memory, instant. */
    fun localResults(query: String): List<Result> =
        if (query.isBlank()) emptyList() else catalogResults(query)

    /**
     * The EXTERNAL sources only (avtrdb + the two VRCX mirrors), in parallel, each
     * capped by [REMOTE_SOURCE_TIMEOUT_MS], deduped by avtr_ id. File-id-bearing
     * results (VRCX) are contributed back immediately; the avtrdb ones (no file id)
     * are resolved + contributed by the caller's harvestSearchResults. Returns only
     * remote results — the caller merges these behind the already-shown local ones.
     */
    suspend fun remoteFill(context: Context, query: String): List<Result> = coroutineScope {
        if (query.isBlank()) return@coroutineScope emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val remote = (
            listOf(async { withTimeoutOrNull(REMOTE_SOURCE_TIMEOUT_MS) { runCatching { search(query) }.getOrDefault(emptyList()) } ?: emptyList() }) +
            VRCX_MIRRORS.map { m -> async { withTimeoutOrNull(REMOTE_SOURCE_TIMEOUT_MS) { vrcxResults("$m?search=$q") } ?: emptyList() } }
        ).awaitAll().flatten()
        val merged = LinkedHashMap<String, Result>()
        for (r in remote) {
            val key = r.id.trim().lowercase()
            if (key.startsWith("avtr_")) merged.putIfAbsent(key, r)
        }
        val list = merged.values.toList()
        // Fill our DB from the file-id-bearing results (free — no VRChat call).
        for (r in list) {
            val fid = r.imageFileId
            if (fid != null && r.source != "catalog") {
                AvatarGlobalDb.contribute(context, fid, r.id, r.name, r.author, r.authorId, r.platforms)
            }
        }
        list
    }

    /** VRCX-style mirror results, parsed richly (image url + platforms) for display. */
    private suspend fun vrcxResults(url: String): List<Result> = withContext(Dispatchers.IO) {
        val body = httpGet(url) ?: return@withContext emptyList()
        try {
            val arr: JSONArray = if (body.trimStart().startsWith("[")) JSONArray(body)
                else JSONObject(body).let { it.optJSONArray("results") ?: it.optJSONArray("avatars") }
                    ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val a = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = a.optString("id", "").ifBlank { a.optString("avatarId", "") }.ifBlank { a.optString("vrc_id", "") }
                if (!id.startsWith("avtr_")) return@mapNotNull null
                val author = a.optString("authorName", "").ifBlank { a.optJSONObject("author")?.optString("name", "") ?: "" }
                val authorId = a.optString("authorId", "").ifBlank { a.optJSONObject("author")?.optString("vrc_id", "") ?: "" }
                val img = a.optString("thumbnailImageUrl", "").ifBlank { a.optString("imageUrl", "") }
                val plats = a.optJSONArray("platforms")?.let { pa ->
                    (0 until pa.length()).mapNotNull { pa.optString(it, "").takeIf { s -> s.isNotBlank() } }
                        .map { prettyAvtrdbPlatform(it) }.filter { it.isNotBlank() }.distinct()
                } ?: emptyList()
                Result(id, a.optString("name", ""), author, img, plats, authorId, FILE_ID.find(img)?.value, "vrcx")
            }
        } catch (e: Exception) { emptyList() }
    }

    /** Our own catalog as search results (image reconstructed from the file id). */
    private fun catalogResults(query: String): List<Result> =
        AvatarGlobalDb.searchByName(query).map { e ->
            Result(
                id = e.avatarId, name = e.name, author = e.author,
                imageUrl = "https://api.vrchat.cloud/api/1/image/${e.fileId}/1/256",
                platforms = e.platforms, authorId = e.authorId, imageFileId = e.fileId, source = "catalog"
            )
        }

    // ---- per-DB health (Settings -> Debug) -----------------------------------

    /** Probe each avatar source individually so a broken/misconfigured setup is
     *  visible on-device. Uses a fixed common query. */
    suspend fun dbHealthCheck(): String = coroutineScope {
        val q = URLEncoder.encode("cat", "UTF-8")
        val targets = mutableListOf(
            "avtrdb" to "$BASE?query=$q&page=0",
            "worker" to "${AvatarGlobalDb.WORKER_URL}/health"
        )
        VRCX_MIRRORS.forEach { m ->
            val host = runCatching { URL(m).host }.getOrNull() ?: "mirror"
            targets.add(host to "$m?search=$q")
        }
        val probes = targets.map { (name, url) ->
            async { val (ok, info) = httpProbe(url); "$name: ${if (ok) "OK" else "FAIL"} $info" }
        }
        (probes.awaitAll() + "catalog(local): ${AvatarGlobalDb.entryCount()} entries").joinToString("\n")
    }

    private suspend fun httpProbe(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            val code = conn.responseCode
            val ok = code in 200..299
            val len = if (ok) (conn.inputStream.bufferedReader().readText().length) else 0
            ok to ("http $code" + if (ok) " (${len}b)" else "")
        } catch (e: Exception) { false to e.javaClass.simpleName }
        finally { runCatching { conn?.disconnect() } }
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

    /**
     * On-headset diagnostics for avatar-id resolution — so a Quest user (who can't
     * read logcat) can see in Settings -> Debug whether each resolve path worked.
     * The `authorListing` line is the KEY one: it distinguishes "blocked (403)" from
     * "returned data but it's NOT the author's avatars (request ignored)" from
     * "worked, matched" from "worked, avatar not in the author's public list".
     */
    object Diag {
        private const val MAX = 12
        private val entries = ArrayDeque<String>()
        /** Result of the most recent official author-avatars-listing attempt. */
        @Volatile var authorListing: String = "(not attempted yet)"
        /** Why the most recent resolve landed where it did (which path / why a miss). */
        @Volatile var lastReason: String = ""

        @Synchronized fun record(line: String) {
            entries.addFirst(line)
            while (entries.size > MAX) entries.removeLast()
        }

        @Synchronized fun dump(): String = buildString {
            append("Author listing: ").append(authorListing)
            if (entries.isNotEmpty()) {
                append("\n\nLast resolves:\n")
                append(entries.joinToString("\n"))
            } else {
                append("\n\n(no resolves yet — open an instance)")
            }
        }
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
