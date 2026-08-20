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
    // Flush contributions only after this many have queued (else the 30-min loop / app
    // open / report paths flush them) — so many contributions become ONE /contribute
    // POST = one KV write, keeping us under Cloudflare's free write/delete budget.
    private const val CONTRIBUTE_FLUSH_THRESHOLD = 100

    data class Entry(
        val fileId: String,
        val avatarId: String,
        val name: String,
        val author: String,
        val authorId: String,
        val platforms: List<String>,
        /** Last time the bot verified this avatar is alive (epoch ms; 0 = never).
         *  The passive sweep picks the OLDEST-checked first. */
        val checked: Long = 0L,
        /** Avatar description/bio (device- or bot-filled; may be genuinely empty). */
        val description: String = "",
        /** The bot has done a full first-fill (name/author/platforms/bio). Devices
         *  contribute filled=false; only the fill bot sets it true. */
        val filled: Boolean = false
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
            harvestLibrary(app)
            while (isActive) {
                delay(REFRESH_MS)
                refresh(app)
                harvestOwnAvatar(app)
                harvestLibrary(app)
                flushQueue(app)
            }
        }
    }

    // ---- lookups (used by the resolver + avatar search) ----------------------

    /** Resolve a worn avatar by its image file id (exact, offline, zero network). */
    fun lookup(fileId: String?): Entry? = fileId?.let { map[it] }

    /** Number of catalog entries currently loaded (for the debug panels). */
    fun entryCount(): Int = map.size

    /** A snapshot of every catalog entry — for the admin dead-check/refresh sweep. */
    fun snapshot(): List<Entry> = map.values.toList()

    /** Force a fresh pull of the catalog file (used by the admin sweep before it
     *  walks entries, so it works on the latest data). */
    fun forceRefresh(context: Context) { scope.launch { refresh(context.applicationContext) } }

    /** POST authoritative admin ops (upserts/removes) to the Worker /admin endpoint.
     *  `upserts` = entries to overwrite (refreshed fields), `removeFileIds` = dead.
     *  Returns true on a 2xx. Admin build only (needs the ADMIN_KEY). */
    suspend fun adminPush(
        context: Context, adminKey: String,
        upserts: List<Entry>, removeFileIds: List<String>,
        clearReports: List<String> = emptyList(), checkedFileIds: List<String> = emptyList()
    ): Boolean {
        if (adminKey.isBlank()) return false
        val body = JSONObject().apply {
            put("key", adminKey)
            put("upserts", JSONArray().apply {
                upserts.forEach { e ->
                    put(JSONObject().apply {
                        put("fileId", e.fileId); put("avatarId", e.avatarId)
                        put("name", e.name); put("author", e.author); put("authorId", e.authorId)
                        put("platforms", JSONArray(e.platforms))
                        put("description", e.description)
                        put("filled", e.filled)
                    })
                }
            })
            put("removes", JSONArray(removeFileIds))
            put("clearReports", JSONArray(clearReports))
            put("checked", JSONArray(checkedFileIds))
        }.toString()
        return kotlinx.coroutines.withContext(Dispatchers.IO) { post("$WORKER_URL/admin", body) }
    }

    /** Optimistically bump the local `checked` time so the passive sweep advances
     *  through the catalog without waiting for the repo round-trip. */
    fun markCheckedLocally(fileIds: Collection<String>) {
        val now = System.currentTimeMillis()
        for (fid in fileIds) map[fid]?.let { map[fid] = it.copy(checked = now) }
    }

    /** Cheap pending-report count from /health (a single KV read on the Worker, no
     *  list op) — the sweep checks this first and only does the heavier /admin/reports
     *  list when there's actually something to verify. */
    suspend fun pendingReportCount(): Int = kotlinx.coroutines.withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$WORKER_URL/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != 200) return@withContext 0
            JSONObject(conn.inputStream.bufferedReader().readText()).optInt("reports", 0)
        } catch (e: Exception) { 0 } finally { runCatching { conn?.disconnect() } }
    }

    /** A pending dead/rename report the admin bot should verify. */
    data class Report(val fileId: String, val avatarId: String, val status: String)

    /** Fetch the PENDING reports from the Worker so the bot verifies only those
     *  (not the whole catalog). Admin build only (needs ADMIN_KEY). */
    suspend fun fetchReports(adminKey: String): List<Report> = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (adminKey.isBlank()) return@withContext emptyList()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$WORKER_URL/admin/reports?key=${java.net.URLEncoder.encode(adminKey, "UTF-8")}")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 12_000; readTimeout = 12_000
            }
            if (conn.responseCode != 200) return@withContext emptyList()
            val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("reports")
                ?: return@withContext emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val f = o.optString("fileId", ""); if (!f.startsWith("file_")) return@mapNotNull null
                Report(f, o.optString("avatarId", ""), o.optString("status", "dead"))
            }
        } catch (e: Exception) { emptyList() } finally { runCatching { conn?.disconnect() } }
    }

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
        name: String, author: String, authorId: String = "", platforms: List<String> = emptyList(),
        description: String = ""
    ) {
        // Only add entries we ACTUALLY have a valid avatar id + file id for.
        if (!FILE_RE.matches(fileId)) return
        if (!AVTR_RE.matches(avatarId)) return
        if (map.containsKey(fileId)) return
        // Insert into the LOCAL catalog immediately so the contributing device can see
        // its own new avatars (own uploads, favourites, resolved strangers) in search /
        // clone RIGHT AWAY — no waiting for the Worker flush + next 30-min pull. Zero
        // extra KV cost (this is a purely in-memory local add).
        map[fileId] = Entry(fileId, avatarId, name, author, authorId, platforms,
            System.currentTimeMillis(), description, false)
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
                if (description.isNotBlank()) put("description", description)
            })
            prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
            contributedCount++
            lastContributed = "${name.ifBlank { avatarId }} (${nowShort()})"
            // Do NOT flush per contribution. That made ONE /contribute POST (= one KV
            // write, plus a pend: key the flush later deletes) PER avatar — harvesting
            // hundreds of candidates blew Cloudflare's tiny free KV write/delete budget.
            // Queue locally and flush only when a big batch has accumulated, plus on the
            // 30-min loop / app open / report — so N contributions collapse into ONE POST.
            if (arr.length() >= CONTRIBUTE_FLUSH_THRESHOLD) flushQueue(app)
        }
    }

    /** Report an entry as dead (404/private) or renamed so the file self-heals. The
     *  avatarId lets the admin bot verify it WITHOUT a catalog lookup, so the bot only
     *  ever checks REPORTED avatars (scales to a huge catalog). */
    fun report(context: Context, fileId: String, avatarId: String, status: String, name: String? = null) {
        if (!fileId.startsWith("file_")) return
        val app = context.applicationContext
        scope.launch {
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val arr = JSONArray(prefs.getString(KEY_REPORTS, "[]"))
            // Dedup within the local queue by file id.
            for (i in 0 until arr.length()) {
                if (arr.optJSONObject(i)?.optString("fileId") == fileId) return@launch
            }
            arr.put(JSONObject().apply {
                put("fileId", fileId); put("avatarId", avatarId); put("status", status)
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
                    o.optString("author", ""), o.optString("authorId", ""), plats,
                    o.optLong("checked", o.optLong("added", 0L)),
                    o.optString("desc", o.optString("description", "")),
                    o.optBoolean("filled", false)
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
            contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
        } catch (ex: Exception) { Log.w(TAG, "avatar-change harvest failed", ex) }
    }

    /** Fill the catalog from SEARCH results that lacked a file id (avtrdb proxies its
     *  images). Resolves each via GET /avatars/{id} (public-only, also fills platforms)
     *  paced + capped, so searching slowly absorbs avtrdb too. Fire-and-forget. */
    fun harvestSearchResults(context: Context, results: List<AvatarSearch.Result>) {
        val app = context.applicationContext
        scope.launch {
            var n = 0
            for (r in results) {
                if (n >= 300) break                           // generous bound for a huge search
                if (r.imageFileId != null) continue           // already contributed in searchAll
                val fid = try { VrchatAuthManager.avatarCatalogEntry(app, r.id)?.fileId }
                    catch (e: Exception) { null } ?: continue // null also = private/dead (skipped)
                if (map.containsKey(fid)) continue
                contribute(app, fid, r.id, r.name, r.author, r.authorId, r.platforms)
                n++
                delay(600)  // pace VRChat REST
            }
        }
    }

    // Candidate ids we've already attempted to harvest this session (so repeated
    // roster publishes / searches don't re-fetch the same clone candidates). Resets
    // on restart.
    private val harvestedCandidates = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Harvest a batch of avatar ids (e.g. EVERY candidate a clone/name search
     *  surfaced, not just the one we cloned) into the catalog — each is a real avatar,
     *  so resolving its own file id + platforms is a free coverage grab. Deduped
     *  against the catalog + a session set, paced + capped so a big instance can't
     *  storm VRChat REST. Fire-and-forget. */
    fun harvestAvatarIds(context: Context, avatarIds: List<String>) {
        val app = context.applicationContext
        scope.launch {
            var n = 0
            for (id in avatarIds) {
                if (n >= 300) break                           // generous per-call bound (session dedup covers the rest)
                if (!AVTR_RE.matches(id)) continue
                if (!harvestedCandidates.add(id)) continue    // already attempted this session
                val e = try { VrchatAuthManager.avatarCatalogEntry(app, id) } catch (ex: Exception) { null }
                    ?: continue                               // null = private/dead/transient (skipped)
                if (map.containsKey(e.fileId)) continue
                contribute(app, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
                n++
                delay(700)  // pace VRChat REST (low-priority background)
            }
        }
    }

    /** Seed the catalog from the user's OWN uploaded + favourited avatars (all
     *  readable with ids). Once per app open — a big free coverage boost. */
    // Favourite avatar ids we've already resolved this session (avoids re-resolving
    // the whole favourites list every 30-min cycle). Resets on restart.
    private val resolvedFavourites = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private suspend fun harvestLibrary(context: Context) {
        try {
            // 1. Own UPLOADS (with local public<->private detection).
            val lib = VrchatAuthManager.ownAvatarLibrary(context)
            var added = 0; var privateRemoved = 0
            for (a in lib) {
                val e = a.entry
                if (a.isPublic) {
                    contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
                    added++
                } else if (a.ownUpload && map.containsKey(e.fileId)) {
                    // The user made their own PUBLIC avatar private -> report removal
                    // (the admin bot confirms via a 404 on the now-private avatar).
                    report(context, e.fileId, e.avatarId, "dead")
                    privateRemoved++
                }
            }
            // 2. FAVOURITES — resolve each new one (public-only via avatarCatalogEntry).
            val favs = VrchatAuthManager.favouriteAvatarIds(context)
            var favAdded = 0
            for (id in favs) {
                if (resolvedFavourites.contains(id)) continue
                resolvedFavourites.add(id) // mark attempted (retries on next app launch)
                val e = try { VrchatAuthManager.avatarCatalogEntry(context, id) } catch (ex: Exception) { null }
                    ?: continue // null = private/dead/transient — skipped
                contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
                favAdded++
                delay(400) // pace VRChat REST
            }
            ownAvatar = "lib +$added, fav +$favAdded/${favs.size}, ${privateRemoved} now-private ${nowShort()}"
        } catch (ex: Exception) { Log.w(TAG, "library harvest failed", ex) }
    }

    private suspend fun harvestOwnAvatar(context: Context) {
        try {
            val e = VrchatAuthManager.currentAvatarCatalogEntry(context)
            if (e == null) { ownAvatar = "no current avatar (not logged in?) ${nowShort()}"; return }
            ownAvatar = "${e.name.ifBlank { e.avatarId }} ${nowShort()}"
            contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
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
