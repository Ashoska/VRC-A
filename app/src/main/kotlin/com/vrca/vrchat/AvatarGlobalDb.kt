package com.vrca.vrchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val KEY_FAV_PROCESSED = "fav_processed"  // favourite ids resolved once (persistent)
    private const val CACHE_FILE = "avatar_db.json"
    private const val REFRESH_MS = 30 * 60_000L  // every 30 min (+ once on open)
    // Push queued contributions to the Worker every 5 min — frequent enough that each
    // batch lands before the Worker's ~10-min GitHub push (so they don't stagnate),
    // still batched so it's a small number of writes. Contributions POST in chunks of
    // this many entries (the Worker caps a single POST) so a big harvest isn't lost.
    private const val FLUSH_MS = 2 * 60_000L
    private const val CONTRIBUTE_CHUNK = 200
    // Paced DB-search seeding from favourites / worn avatars. Each name runs ONE
    // AvatarSearch.searchAll (avtrdb + 2 VRCX mirrors + our catalog), which contributes
    // any file-id-bearing result back — so it grows the catalog with OTHER avatars indexed
    // under that name, with ZERO VRChat REST (searchAll never resolves file ids itself).
    // Drained one name per SEARCH_SEED_PACE_MS so a big favourites list can't rate-limit
    // the DBs; names deduped per session; queue capped.
    private const val SEARCH_SEED_PACE_MS = 6_000L
    private const val SEARCH_SEED_QUEUE_CAP = 1500   // favourite lists can be ~1000
    private const val SEARCH_SEED_MIN_LEN = 3
    // Favourites can be up to ~1000. Resolving each via VRChat REST would rate-limit, so:
    // skip any already in the catalog (no call), cap NEW resolves per 30-min sweep (spread
    // 1000 across sweeps), and back off on a run of nulls (a 429 burst).
    private const val FAV_RESOLVE_PER_SWEEP = 120
    private const val FAV_PACE_MS = 600L
    private const val FAV_RL_BACKOFF = 5             // consecutive nulls => assume rate-limited, stop this sweep

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
        /** Per-platform performance/optimisation rank (bot-filled from unityPackages):
         *  0=Excellent 1=Good 2=Medium 3=Poor 4=VeryPoor 5=None/unknown. */
        val perfPc: Int = 5,
        val perfQuest: Int = 5,
        val perfIos: Int = 5,
        /** The bot has done a full first-fill (name/author/platforms/bio). Devices
         *  contribute filled=false; only the fill bot sets it true. */
        val filled: Boolean = false
    )

    private val map = ConcurrentHashMap<String, Entry>()   // fileId -> entry
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serializes all read-modify-write of the persisted contribution queue so a flush
    // (which drains it) can't race a concurrent contribute (which appends) and lose it.
    private val queueMutex = Mutex()

    // Paced DB-search seed queue: names from favourites / worn avatars, drained one at a
    // time by a slow timer so a big favourites list can't rate-limit the DBs. Deduped per
    // session so the same name is never re-searched.
    private val searchSeedQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val seededSearchNames = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val FILE_RE = Regex("""file_[0-9a-fA-F-]{36}""")
    private val AVTR_RE = Regex("""avtr_[0-9a-fA-F-]{36}""")

    @Volatile private var lastPull = "never"
    @Volatile private var lastPost = "none"
    @Volatile private var ownAvatar = "not harvested yet"
    @Volatile private var lastSwitch = "no avatar switch seen yet"
    @Volatile private var lastFav = "no favourites sweep yet"
    @Volatile private var lastContributed = "none"
    @Volatile private var contributedCount = 0
    @Volatile private var lastOwnHarvestMs = 0L
    @Volatile private var lastSeedSearch = "none"

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
            loadProcessedFavourites(app)
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
        // Dedicated periodic flush so queued contributions reach the Worker REGULARLY
        // (well within its ~10-min GitHub push window) instead of stagnating until the
        // 30-min refresh loop. The queue is persisted to SharedPreferences, so it also
        // survives the app being closed and is drained on the next open / next tick.
        scope.launch {
            while (isActive) {
                delay(FLUSH_MS)
                flushQueue(app)
            }
        }
        // Paced DB-search seeder: drain ONE queued name per SEARCH_SEED_PACE_MS. searchAll
        // hits avtrdb + 2 VRCX mirrors + our catalog and contributes any file-id-bearing
        // result back — growing the catalog with other avatars indexed under that name, with
        // ZERO VRChat REST (searchAll never resolves file ids). One name/10s keeps the DBs
        // well under any rate limit no matter how many favourites are queued.
        scope.launch {
            while (isActive) {
                val name = searchSeedQueue.poll()
                if (name == null) { delay(SEARCH_SEED_PACE_MS); continue }
                runCatching { AvatarSearch.searchAll(app, name) }
                lastSeedSearch = "'$name' (${searchSeedQueue.size} queued) ${nowShort()}"
                delay(SEARCH_SEED_PACE_MS)
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

    /** Force a fresh pull of the catalog file. `cacheBust` (e.g. the Worker's lastFlush
     *  timestamp) appends a query param so GitHub's CDN can't serve a STALE cached copy —
     *  used the moment the Worker reports a new flush so new avatars land immediately. */
    fun forceRefresh(context: Context, cacheBust: String? = null) {
        scope.launch { refresh(context.applicationContext, cacheBust) }
    }

    /** The Worker's last-flush timestamp (cheap /health read) — changes each time the
     *  file is rewritten, so the admin can pull the new file the instant it updates. */
    fun workerLastFlush(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$WORKER_URL/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != 200) return null
            JSONObject(conn.inputStream.bufferedReader().readText()).optString("lastFlush", "")
                .takeIf { it.isNotBlank() }
        } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
    }

    /** A CONTENT signal (cheap /health read) that changes ONLY when the catalog's
     *  contents actually change — new avatars merged (`totalAdded`), removals
     *  (`totalRemoved` / `entries`). Unlike [workerLastFlush] (which advances every
     *  2-min cron even on a no-op flush), keying the admin refresh off this pulls the
     *  full file ONLY when there's really something new — so a newly-added avatar
     *  reaches the FILL bot promptly, without a wasteful 13MB re-parse every 2 min. */
    fun workerContentSignal(): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$WORKER_URL/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != 200) return null
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            "${j.optInt("entries", -1)}:${j.optInt("totalAdded", 0)}:${j.optInt("totalRemoved", 0)}"
        } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
    }

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
                        put("perfPc", e.perfPc); put("perfQuest", e.perfQuest); put("perfIos", e.perfIos)
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

    /** Apply admin bot ops to the LOCAL in-memory catalog IMMEDIATELY (upserts set
     *  filled/refreshed fields + bump checked; removes drop the entry) so the admin's
     *  backlog counts drop live as the bots work, instead of waiting ~15 min for the
     *  Worker flush + re-pull. The authoritative copy is still the Worker's. */
    fun applyAdminLocal(upserts: List<Entry>, removes: Collection<String>) {
        val now = System.currentTimeMillis()
        for (fid in removes) map.remove(fid)
        for (e in upserts) map[e.fileId] = e.copy(checked = now)
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

    /**
     * Search the catalog for the in-app avatar search. TOKEN-based across the avatar's
     * NAME + AUTHOR + **DESCRIPTION** (bio) — so an avatar is findable by words in its
     * description, not just its name (e.g. "cute fox" finds one whose bio says "a cute
     * fox avatar" even if it's named "Foxxo"). Every query word must appear SOMEWHERE
     * (AND), and results are ranked: a name hit weighs most, then author, then bio, with
     * an exact/prefix name boost. This is the richer coverage the public name-only DBs
     * can't offer — it grows as the fill bot backfills descriptions.
     */
    fun searchByName(query: String, limit: Int = 60): List<Entry> {
        val ql = query.trim().lowercase()
        val tokens = ql.split(Regex("\\s+")).filter { it.length >= 2 }
        if (tokens.isEmpty()) return emptyList()
        val scored = ArrayList<Pair<Entry, Int>>()
        for (e in map.values) {
            val name = e.name.lowercase()
            val author = e.author.lowercase()
            val desc = e.description.lowercase()
            val hay = "$name $author $desc"
            if (!tokens.all { hay.contains(it) }) continue   // AND — every word must match
            var score = 0
            for (t in tokens) score += when {
                name.contains(t) -> 5
                author.contains(t) -> 2
                else -> 1                                     // description-only hit
            }
            if (name == ql) score += 20 else if (name.startsWith(tokens.first())) score += 3
            scored.add(e to score)
        }
        return scored.sortedByDescending { it.second }
            .map { it.first }.distinctBy { it.avatarId }.take(limit)
    }

    // ---- contribute / report -------------------------------------------------

    /** Queue a newly-learned mapping and try to send it. No-op if we already have
     *  this file id (locally known = already in the global file or queued). */
    fun contribute(
        context: Context, fileId: String, avatarId: String,
        name: String, author: String, authorId: String = "", platforms: List<String> = emptyList(),
        description: String = ""
    ): Boolean {
        // Only add entries we ACTUALLY have a valid avatar id + file id for.
        // Returns TRUE only when this call adds a genuinely NEW entry — so harvest
        // callers can report "new" vs "already in catalog" (the common case for a
        // mature catalog, where a worn/favourited avatar is usually already present).
        if (!FILE_RE.matches(fileId)) return false
        if (!AVTR_RE.matches(avatarId)) return false
        if (map.containsKey(fileId)) return false
        // Insert into the LOCAL catalog immediately so the contributing device can see
        // its own new avatars (own uploads, favourites, resolved strangers) in search /
        // clone RIGHT AWAY — no waiting for the Worker flush + next 30-min pull. Zero
        // extra KV cost (this is a purely in-memory local add).
        map[fileId] = Entry(fileId, avatarId, name, author, authorId, platforms,
            System.currentTimeMillis(), description, filled = false)
        val app = context.applicationContext
        scope.launch {
            queueMutex.withLock {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val arr = JSONArray(prefs.getString(KEY_QUEUE, "[]"))
                // Dedup within the queue by file id.
                for (i in 0 until arr.length()) {
                    if (arr.optJSONObject(i)?.optString("fileId") == fileId) return@withLock
                }
                arr.put(JSONObject().apply {
                    put("fileId", fileId); put("avatarId", avatarId)
                    put("name", name); put("author", author); put("authorId", authorId)
                    put("platforms", JSONArray(platforms))
                    if (description.isNotBlank()) put("description", description)
                })
                // Persist to disk (survives app close; drained on the 5-min flush loop /
                // next open). Do NOT flush per contribution — the periodic flush batches.
                prefs.edit().putString(KEY_QUEUE, arr.toString()).apply()
                contributedCount++
                lastContributed = "${name.ifBlank { avatarId }} (${nowShort()})"
            }
        }
        return true
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
        // Contributions. DRAIN the queue under the lock (take ownership, clear it) so a
        // concurrent contribute can only append AFTER we've taken these — nothing is
        // lost. POST outside the lock (network is slow); on failure, re-queue the unsent
        // tail merged in FRONT of anything appended meanwhile.
        val items = queueMutex.withLock {
            val q = JSONArray(prefs.getString(KEY_QUEUE, "[]"))
            if (q.length() == 0) emptyList() else {
                prefs.edit().putString(KEY_QUEUE, "[]").apply()
                (0 until q.length()).mapNotNull { q.optJSONObject(it) }
            }
        }
        if (items.isNotEmpty()) {
            var sent = 0
            while (sent < items.size) {
                val end = minOf(sent + CONTRIBUTE_CHUNK, items.size)
                val chunk = JSONArray().apply { for (i in sent until end) put(items[i]) }
                if (!post("$WORKER_URL/contribute", JSONObject().put("entries", chunk).toString())) break
                sent = end
            }
            if (sent >= items.size) {
                lastPost = "sent ${items.size} at ${nowShort()}"
            } else {
                // Re-queue the unsent tail (in front of anything appended during the POST).
                queueMutex.withLock {
                    val cur = JSONArray(prefs.getString(KEY_QUEUE, "[]"))
                    val merged = JSONArray()
                    for (i in sent until items.size) merged.put(items[i])
                    for (j in 0 until cur.length()) cur.optJSONObject(j)?.let { merged.put(it) }
                    prefs.edit().putString(KEY_QUEUE, merged.toString()).apply()
                }
                lastPost = "sent $sent/${items.size} at ${nowShort()} (retrying rest)"
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

    private fun refresh(context: Context, cacheBust: String? = null) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Read the file from EXACTLY where the Worker writes it — learn the URL from
        // /health (echoes rawUrl), so no repo/branch/path mismatch is possible.
        val baseUrl = fetchWorkerRawUrl()
            ?: prefs.getString(KEY_RAWURL, null)
            ?: "https://raw.githubusercontent.com/$REPO/main/$DB_PATH"
        prefs.edit().putString(KEY_RAWURL, baseUrl).apply()
        // For a fresh pull (cacheBust set), read STRAIGHT FROM THE WORKER (/db, served
        // from KV) — GitHub's raw CDN caches ~5 min and ignores cache-busting query
        // params, which delayed the admin bots seeing new avatars. The normal (public)
        // refresh still uses the CDN with an ETag (free, fine at 30-min cadence).
        val rawUrl = if (cacheBust != null) "$WORKER_URL/db" else baseUrl
        val etag = if (cacheBust == null) prefs.getString(KEY_ETAG, null) else null
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
            // SAFETY: never replace a populated local catalog with an empty one (a blank
            // /db before the first flush, a truncated read, etc.) — that would wipe it.
            if (avatars.length() == 0 && map.isNotEmpty()) return
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
                val fileEntry = Entry(
                    fileId, id, o.optString("name", ""),
                    o.optString("author", ""), o.optString("authorId", ""), plats,
                    o.optLong("checked", o.optLong("added", 0L)),
                    o.optString("desc", o.optString("description", "")),
                    o.optInt("perfPc", 5), o.optInt("perfQuest", 5), o.optInt("perfIos", 5),
                    o.optBoolean("filled", false)
                )
                fresh[fileId] = mergeWithLocal(fileEntry, map[fileId])
            }
            map.clear(); map.putAll(fresh)
        } catch (e: Exception) { Log.w(TAG, "parse failed", e) }
    }

    /** When re-pulling the file, PRESERVE local progress that's ahead of it — the bot
     *  filled/checked an avatar but the Worker file hasn't flushed that yet. `filled` and
     *  `checked` only ever advance (monotonic), and while local is ahead we keep the
     *  locally-filled fields too — so the fill/liveness bots never RE-DO an avatar they've
     *  already done just because a stale file pull momentarily reset it. Once the file
     *  catches up (has filled=true) it takes over. */
    private fun mergeWithLocal(file: Entry, local: Entry?): Entry {
        if (local == null) return file
        val keepFill = local.filled && !file.filled
        return file.copy(
            filled = local.filled || file.filled,
            checked = maxOf(local.checked, file.checked),
            name = if (keepFill) local.name.ifBlank { file.name } else file.name.ifBlank { local.name },
            author = if (keepFill) local.author.ifBlank { file.author } else file.author.ifBlank { local.author },
            authorId = if (keepFill) local.authorId.ifBlank { file.authorId } else file.authorId.ifBlank { local.authorId },
            platforms = if (keepFill && file.platforms.isEmpty()) local.platforms else file.platforms.ifEmpty { local.platforms },
            description = if (keepFill && file.description.isBlank()) local.description else file.description,
            perfPc = if (keepFill && file.perfPc == 5) local.perfPc else file.perfPc,
            perfQuest = if (keepFill && file.perfQuest == 5) local.perfQuest else file.perfQuest,
            perfIos = if (keepFill && file.perfIos == 5) local.perfIos else file.perfIos
        )
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
            val e = VrchatAuthManager.avatarCatalogEntry(context, avatarId)
            if (e == null) {
                lastSwitch = "switched to a non-public avatar (not contributed) ${nowShort()}"
                AvatarSearch.Diag.record("you worn -> non-public avatar (not contributed)")
                return
            }
            val added = contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
            lastSwitch = "switched -> ${e.name.ifBlank { e.avatarId }} — " +
                "${if (added) "CONTRIBUTED (new)" else "already in catalog"} ${nowShort()}"
            AvatarSearch.Diag.record("you worn -> ${e.name.ifBlank { e.avatarId }}: " +
                if (added) "contributed (new)" else "already in catalog")
            seedSearchFromName(e.name)   // grow the catalog from this worn avatar's name
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
     *  readable with ids). Each favourite is resolved EXACTLY ONCE, ever. */
    // Favourite ids already resolved (contributed / confirmed dead / private). PERSISTED to
    // disk so a favourite is never re-resolved across restarts — the whole point of "run each
    // favourite once and then not again" at ~1000-favourite scale. Loaded on start.
    private val processedFavourites = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var processedFavLoaded = false

    private fun loadProcessedFavourites(context: Context) {
        if (processedFavLoaded) return
        processedFavLoaded = true
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_FAV_PROCESSED, "") ?: ""
            if (raw.isNotBlank()) raw.split('\n').forEach { if (it.startsWith("avtr_")) processedFavourites.add(it) }
        } catch (e: Exception) { Log.w(TAG, "load processed favourites failed", e) }
    }

    private fun saveProcessedFavourites(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_FAV_PROCESSED, processedFavourites.joinToString("\n")).apply()
        } catch (e: Exception) { Log.w(TAG, "save processed favourites failed", e) }
    }

    private suspend fun harvestLibrary(context: Context) {
        try {
            // 1. Own UPLOADS (with local public<->private detection).
            val lib = VrchatAuthManager.ownAvatarLibrary(context)
            var libNew = 0; var libKnown = 0; var privateRemoved = 0
            for (a in lib) {
                val e = a.entry
                if (a.isPublic) {
                    if (contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description))
                        libNew++ else libKnown++
                } else if (a.ownUpload && map.containsKey(e.fileId)) {
                    // The user made their own PUBLIC avatar private -> report removal
                    // (the admin bot confirms via a 404 on the now-private avatar).
                    report(context, e.fileId, e.avatarId, "dead")
                    privateRemoved++
                }
            }
            // 2. FAVOURITES — up to ~1000, each resolved EXACTLY ONCE, ever (processedFavourites
            //    is persistent). That one pass IS the dead-check: a favourite already in our
            //    catalog that now 404s is REPORTED to the admin bots to double-check + remove;
            //    a public one not yet in the catalog is contributed. Capped per sweep + backs
            //    off on a run of UNAVAILABLE (429) so ~1000 favourites spread across sweeps
            //    without rate-limiting VRChat; UNAVAILABLE ids are NOT marked, so they retry.
            //    After a favourite is processed once it's never fetched again (the admin bots'
            //    7-day liveness sweep covers ongoing death of catalog entries).
            val favs = VrchatAuthManager.favouriteAvatarIds(context)
            val knownById = HashMap<String, AvatarGlobalDb.Entry>(map.size)
            for (e in map.values) knownById[e.avatarId] = e
            var favNew = 0; var favKnown = 0; var favSkipped = 0; var favDead = 0; var favProcessed = 0
            var consecutiveUnavail = 0; var rateLimited = false
            for (id in favs) {
                if (processedFavourites.contains(id)) continue
                if (favProcessed >= FAV_RESOLVE_PER_SWEEP) continue   // cap per sweep; rest next sweep
                val known = knownById[id]
                val res = VrchatAuthManager.avatarCatalogEntryDetailed(context, id)
                when (res.status) {
                    VrchatAuthManager.AvatarFetch.UNAVAILABLE -> {
                        // 429/network — don't mark; a run of these = rate-limited, stop the sweep.
                        if (++consecutiveUnavail >= FAV_RL_BACKOFF) { rateLimited = true; break }
                        delay(FAV_PACE_MS); continue
                    }
                    VrchatAuthManager.AvatarFetch.DEAD -> {
                        consecutiveUnavail = 0; processedFavourites.add(id); favProcessed++
                        // Only reportable if it's in our catalog (report is keyed by file id);
                        // an unknown dead favourite was never in the catalog, so nothing to remove.
                        if (known != null) { report(context, known.fileId, known.avatarId, "dead"); favDead++ }
                        AvatarSearch.Diag.record("fav -> ${known?.name ?: id}: DEAD${if (known != null) " (reported)" else ""}")
                    }
                    VrchatAuthManager.AvatarFetch.PRIVATE -> {
                        consecutiveUnavail = 0; processedFavourites.add(id); favProcessed++; favSkipped++
                        known?.let { seedSearchFromName(it.name) }
                    }
                    VrchatAuthManager.AvatarFetch.FOUND -> {
                        consecutiveUnavail = 0; processedFavourites.add(id); favProcessed++
                        val e = res.entry!!
                        val favNewOne = contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
                        if (favNewOne) favNew++ else favKnown++
                        AvatarSearch.Diag.record("fav -> ${e.name.ifBlank { e.avatarId }}: " +
                            if (favNewOne) "contributed (new)" else "already in catalog")
                        seedSearchFromName(e.name)
                    }
                }
                delay(FAV_PACE_MS)
            }
            if (favProcessed > 0) saveProcessedFavourites(context)
            lastFav = "favourites ${favs.size}: $favNew new / $favKnown known" +
                (if (favDead > 0) " / $favDead dead-reported" else "") +
                (if (favSkipped > 0) " / $favSkipped private" else "") +
                (if (rateLimited) " · rate-limited, resuming next sweep" else "") +
                " · ${processedFavourites.size} done all-time · uploads +$libNew" +
                (if (privateRemoved > 0) " · $privateRemoved now-private" else "") +
                " ${nowShort()}"
        } catch (ex: Exception) { Log.w(TAG, "library harvest failed", ex) }
    }

    /** Harvest the user's CURRENTLY-WORN avatar RIGHT NOW (public-only). Called when
     *  the pipeline detects the user changed their own avatar, so a newly-worn PUBLIC
     *  avatar is contributed within seconds instead of waiting for the next 30-min
     *  cycle / app reopen. Debounced (>=8s apart) so the 10s presence loop can't spam
     *  it; a PRIVATE avatar is still skipped by avatarCatalogEntry (privacy). */
    /** Queue a name (from a favourite / worn avatar) for a paced DB search that grows the
     *  catalog with OTHER avatars indexed under it. Deduped per session; capped; drained one
     *  name per SEARCH_SEED_PACE_MS by the loop in [start] — so it never bursts the DBs. */
    fun seedSearchFromName(name: String) {
        val n = name.trim()
        if (n.length < SEARCH_SEED_MIN_LEN) return
        if (!seededSearchNames.add(n.lowercase())) return          // already seeded this session
        if (searchSeedQueue.size >= SEARCH_SEED_QUEUE_CAP) return   // bounded
        searchSeedQueue.add(n)
    }

    fun harvestOwnAvatarNow(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastOwnHarvestMs < 8_000) return
        lastOwnHarvestMs = now
        val app = context.applicationContext
        scope.launch { harvestOwnAvatar(app) }
    }

    private suspend fun harvestOwnAvatar(context: Context) {
        try {
            val e = VrchatAuthManager.currentAvatarCatalogEntry(context)
            if (e == null) {
                ownAvatar = "current avatar not public / not logged in (not contributed) ${nowShort()}"
                return
            }
            val added = contribute(context, e.fileId, e.avatarId, e.name, e.author, e.authorId, e.platforms, e.description)
            ownAvatar = "${e.name.ifBlank { e.avatarId }} — " +
                "${if (added) "CONTRIBUTED (new)" else "already in catalog"} ${nowShort()}"
            // Only record NEW contributions here — this runs every 30 min, so logging an
            // "already in catalog" line each cycle would flood the resolves log.
            if (added) AvatarSearch.Diag.record("you current -> ${e.name.ifBlank { e.avatarId }}: contributed (new)")
            seedSearchFromName(e.name)   // grow the catalog from this avatar's name
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
            "current avatar: $ownAvatar\n" +
            "last switch: $lastSwitch\n" +
            "favourites/uploads: $lastFav\n" +
            "seed search: $lastSeedSearch (${searchSeedQueue.size} queued)\n" +
            "contributed (new this run)=$contributedCount last=$lastContributed\n" +
            "queue=$q reports=$r\nlastPost=$lastPost"
    }

    private fun nowShort(): String = (android.os.SystemClock.elapsedRealtime() / 1000).let { "+${it}s" }
}
