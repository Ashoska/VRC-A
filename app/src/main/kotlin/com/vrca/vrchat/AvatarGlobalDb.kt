package com.vrca.vrchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crowdsourced avatar-id catalog — the "extra database" that grows with the
 * userbase and covers avatars no public database has indexed.
 *
 * It reads ONE JSON file (`avatars/db.json`) from the image-store repo over the
 * GitHub CDN, and contributes any avatar id it learns back through the Cloudflare
 * Worker (the single writer). **Zero Firestore.**
 *
 *  - **Read:** ETag conditional GET on app open + every 30 min (a 304 when nothing
 *    changed costs almost nothing). Cached to disk so it works offline immediately.
 *  - **Lookup:** by the worn image FILE ID (clone resolution) or by NAME (search).
 *  - **Contribute:** any avatar id we can read (our own worn avatar, a resolve, a
 *    clone, favourites) is queued locally and POSTed to the Worker; the queue
 *    survives outages (Worker/network down) and drains on the next attempt.
 *  - **Report:** an avatar we find dead/renamed is reported so the file self-heals
 *    (the Worker applies renames immediately and removes on a small quorum).
 *
 * The file is keyed by the avatar's canonical thumbnail file id, which is exactly
 * what a stranger's `/users/{id}` exposes — so an id one user contributes is
 * findable by everyone else off the same key.
 */
object AvatarGlobalDb {
    private const val TAG = "AvatarGlobalDb"

    /** Cloudflare Worker base URL (contribute/report/health). */
    const val WORKER_URL = "https://vrca-avatar-db.shadowash321rulse.workers.dev"
    private const val REPO = "Ashoska/VRC-A-Image-store"
    private const val DB_PATH = "avatars/db.json"

    private const val PREFS = "vrca_avatar_db"
    private const val KEY_ETAG = "etag"
    private const val KEY_RAWURL = "rawurl"       // exact file URL learned from the Worker /health
    private const val KEY_QUEUE = "queue"        // pending contributions (JSON array)
    private const val KEY_REPORTS = "reports"    // pending reports (JSON array)
    private const val CACHE_FILE = "avatar_db.json"
    private const val REFRESH_MS = 30 * 60_000L  // every 30 min (+ once on open)

    data class Entry(
        val fileId: String,
        val avatarId: String,
        val name: String,
        val author: String,
        val authorId: String,
        val platforms: List<String>
    )

    private val map = ConcurrentHashMap<String, Entry>()   // fileId -> entry
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val FILE_RE = Regex("""file_[0-9a-fA-F-]{36}""")
    private val AVTR_RE = Regex("""avtr_[0-9a-fA-F-]{36}""")

    @Volatile private var lastPull = "never"
    @Volatile private var lastPost = "none"
    @Volatile private var ownAvatar = "not harvested yet"
    @Volatile private var lastContributed = "none"
    @Volatile private var contributedCount = 0

    // ---- lifecycle -----------------------------------------------------------

    /** Idempotent. First call loads the disk cache, pulls the file, drains the
     *  queue, and starts the 30-min refresh loop. Later calls just kick an
     *  on-open refresh + flush. Safe on every build. */
    fun start(context: Context) {
        val app = context.applicationContext
        if (!started.compareAndSet(false, true)) {
            scope.launch { refresh(app); flushQueue(app); harvestOwnAvatar(app) }
            return
        }
        scope.launch {
            loadLocalCache(app)
            refresh(app)
            flushQueue(app)
            harvestOwnAvatar(app)
            while (isActive) {
                delay(REFRESH_MS)
                refresh(app)
                harvestOwnAvatar(app)
                flushQueue(app)
            }
        }
    }

    // ---- lookups (used by the resolver + avatar search) ----------------------

    /** Resolve a worn avatar by its image file id (exact, offline, zero network). */
    fun lookup(fileId: String?): Entry? = fileId?.let { map[it] }

    /** Number of catalog entries currently loaded (for the debug panels). */
    fun entryCount(): Int = map.size

    /** Name search over the catalog (for the in-app avatar search). */
    fun searchByName(query: String, limit: Int = 30): List<Entry> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        return map.values.asSequence()
            .filter { it.name.lowercase().contains(q) }
            .distinctBy { it.avatarId }
            .take(limit)
            .toList()
    }

    // ---- contribute / report -------------------------------------------------

    /** Queue a newly-learned mapping and try to send it. No-op if we already have
     *  this file id (locally known = already in the global file or queued). */
    fun contribute(
        context: Context, fileId: String, avatarId: String,
        name: String, author: String, authorId: String = "", platforms: List<String> = emptyList()
    ) {
        // Only add entries we ACTUALLY have a valid avatar id + file id for.
        if (!FILE_RE.matches(fileId)) return
        if (!AVTR_RE.matches(avatarId)) return
        if (map.containsKey(fileId)) return
        val app = context.applicationContext
        scope.launch {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_QUEUE, "[]"))
            // Dedup within the queue by file id.
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("fileId") == fileId) return@launch
            }
            arr.put(JSONObject().apply {
                put("fileId", fileId); put("avatarId", avatarId)
                put("name", name); put("author", author); put("authorId", authorId)
                put("platforms", JSONArray(platforms))
            })
            prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
            contributedCount++
            lastContributed = "${name.ifBlank { avatarId }} (${nowShort()})"
            flushQueue(app)
        }
    }

    /** Report an entry as dead (404/private) or renamed so the file self-heals. */
    fun report(context: Context, fileId: String, status: String, name: String? = null) {
        if (!fileId.startsWith("file_")) return
        val app = context.applicationContext
        scope.launch {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_REPORTS, "[]"))
            arr.put(JSONObject().apply {
                put("fileId", fileId); put("status", status)
                if (name != null) put("name", name)
            })
            prefs.edit().putString(KEY_REPORTS, arr.toString()).apply()
            flushQueue(app)
        }
    }

    // ---- worker POST ---------------------------------------------------------

    private suspend fun flushQueue(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Contributions.
        val queue = JSONArray(prefs.getString(KEY_QUEUE, "[]"))
        if (queue.length() > 0) {
            val body = JSONObject().put("entries", queue).toString()
            val ok = post("$WORKER_URL/contribute", body)
            if (ok) {
                prefs.edit().putString(KEY_QUEUE, "[]").apply()
                lastPost = "sent ${queue.length()} at ${nowShort()}"
            } else {
                lastPost = "FAILED (queued ${queue.length()})"
            }
        }
        // Reports (one POST each; small volume).
        val reports = JSONArray(prefs.getString(KEY_REPORTS, "[]"))
        if (reports.length() > 0) {
            var remaining = JSONArray()
            for (i in 0 until reports.length()) {
                val r = reports.optJSONObject(i) ?: continue
                if (!post("$WORKER_URL/report", r.toString())) remaining.put(r)
            }
            prefs.edit().putString(KEY_REPORTS, remaining.toString()).apply()
        }
    }

    private fun post(url: String, body: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "VRC-A")
                doOutput = true
                connectTimeout = 12_000; readTimeout = 12_000
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "POST $url failed: ${e.message}"); false
        } finally { runCatching { conn?.disconnect() } }
    }

    // ---- file read (ETag) ----------------------------------------------------

    private fun loadLocalCache(context: Context) {
        try {
            val f = File(context.filesDir, CACHE_FILE)
            if (f.exists()) parseInto(f.readText())
        } catch (e: Exception) { Log.w(TAG, "cache load failed", e) }
    }

    private fun refresh(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Read the file from EXACTLY where the Worker writes it — learn the URL from
        // /health (echoes rawUrl), so no repo/branch/path mismatch is possible.
        val rawUrl = fetchWorkerRawUrl()
            ?: prefs.getString(KEY_RAWURL, null)
            ?: "https://raw.githubusercontent.com/$REPO/main/$DB_PATH"
        prefs.edit().putString(KEY_RAWURL, rawUrl).apply()
        val etag = prefs.getString(KEY_ETAG, null)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(rawUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "VRC-A")
                if (etag != null) setRequestProperty("If-None-Match", etag)
                connectTimeout = 15_000; readTimeout = 15_000
            }
            when (conn.responseCode) {
                304 -> lastPull = "304 (unchanged) ${nowShort()}"
                200 -> {
                    val text = conn.inputStream.bufferedReader().readText()
                    parseInto(text)
                    File(context.filesDir, CACHE_FILE).writeText(text)
                    conn.getHeaderField("ETag")?.let { prefs.edit().putString(KEY_ETAG, it).apply() }
                    lastPull = "pulled ${map.size} at ${nowShort()}"
                }
                else -> lastPull = "http ${conn.responseCode} at ${nowShort()} ($rawUrl)"
            }
        } catch (e: Exception) {
            lastPull = "error ${e.javaClass.simpleName} ${nowShort()}"
        } finally { runCatching { conn?.disconnect() } }
    }

    /** Ask the Worker where it writes (its /health echoes the exact rawUrl). */
    private fun fetchWorkerRawUrl(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$WORKER_URL/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != 200) return null
            val raw = JSONObject(conn.inputStream.bufferedReader().readText()).optString("rawUrl", "")
            raw.takeIf { it.startsWith("https://raw.githubusercontent.com/") && !it.contains("/?/") }
        } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
    }

    private fun parseInto(text: String) {
        try {
            val avatars = JSONObject(text).optJSONObject("avatars") ?: return
            val fresh = HashMap<String, Entry>(avatars.length())
            val keys = avatars.keys()
            while (keys.hasNext()) {
                val fileId = keys.next()
                val o = avatars.optJSONObject(fileId) ?: continue
                val id = o.optString("id", "")
                if (!id.startsWith("avtr_")) continue
                val plats = o.optJSONArray("platforms")?.let { pa ->
                    (0 until pa.length()).mapNotNull { pa.optString(it, "").takeIf { s -> s.isNotBlank() } }
                } ?: emptyList()
                fresh[fileId] = Entry(
                    fileId, id, o.optString("name", ""),
                    o.optString("author", ""), o.optString("authorId", ""), plats
                )
            }
            map.clear(); map.putAll(fresh)
        } catch (e: Exception) { Log.w(TAG, "parse failed", e) }
    }

    // ---- own-avatar seed -----------------------------------------------------

    /** Contribute the local user's OWN current avatar — the id they can always
     *  read for themselves, which is the coverage the public DBs can't have. */
    @Volatile private var lastOwnAvatarId = ""

    /** Called when VRChat's `/avatar/change` OSC event fires (the user changed into
     *  a new avatar) — harvests that exact avatar immediately, so our OWN avatars
     *  are captured the moment we wear them, not just on the 30-min cycle. Deduped
     *  so a repeated event for the same avatar doesn't re-fetch. */
    fun onAvatarChanged(context: Context?, avatarId: String) {
        if (context == null || !avatarId.startsWith("avtr_")) return
        if (avatarId == lastOwnAvatarId) return
        lastOwnAvatarId = avatarId
        val app = context.applicationContext
        scope.launch { harvestAvatarId(app, avatarId) }
    }

    private suspend fun harvestAvatarId(context: Context, avatarId: String) {
        try {
            val e = VrchatAuthManager.avatarCatalogEntry(context, avatarId) ?: return
            ownAvatar = "${e.name.ifBlank { e.avatarId }} (changed) ${nowShort()}"
            contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms)
        } catch (ex: Exception) { Log.w(TAG, "avatar-change harvest failed", ex) }
    }

    private suspend fun harvestOwnAvatar(context: Context) {
        try {
            val e = VrchatAuthManager.currentAvatarCatalogEntry(context)
            if (e == null) { ownAvatar = "no current avatar (not logged in?) ${nowShort()}"; return }
            ownAvatar = "${e.name.ifBlank { e.avatarId }} ${nowShort()}"
            contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms)
        } catch (ex: Exception) {
            ownAvatar = "error ${ex.javaClass.simpleName}"
            Log.w(TAG, "own-avatar harvest failed", ex)
        }
    }

    // ---- diagnostics ---------------------------------------------------------

    fun diag(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val q = try { JSONArray(prefs.getString(KEY_QUEUE, "[]")).length() } catch (e: Exception) { 0 }
        val r = try { JSONArray(prefs.getString(KEY_REPORTS, "[]")).length() } catch (e: Exception) { 0 }
        return "entries=${map.size}\npull=$lastPull\n" +
            "ownAvatar=$ownAvatar\n" +
            "contributed=$contributedCount last=$lastContributed\n" +
            "queue=$q reports=$r\nlastPost=$lastPost"
    }

    private fun nowShort(): String = (android.os.SystemClock.elapsedRealtime() / 1000).let { "+${it}s" }
}
