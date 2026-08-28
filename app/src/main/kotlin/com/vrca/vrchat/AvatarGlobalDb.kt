package com.vrca.vrchat

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
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
    private const val KEY_SEED_QUEUE = "seed_queue"        // pending seed-search names (persistent)
    private const val CACHE_FILE = "avatar_db.json"
    private const val REFRESH_MS = 30 * 60_000L  // every 30 min (+ once on open)
    // Push queued contributions to the Worker every 5 min — frequent enough that each
    // batch lands before the Worker's ~10-min GitHub push (so they don't stagnate),
    // still batched so it's a small number of writes. Contributions POST in chunks of
    // this many entries (the Worker caps a single POST) so a big harvest isn't lost.
    private const val FLUSH_MS = 2 * 60_000L
    private const val CONTRIBUTE_CHUNK = 200
    // A USER contribution (own upload / favourite / resolved stranger) quick-flushes within
    // this window instead of waiting out the 2-min periodic loop — so it reaches the Worker,
    // gets merged on the next 1-min cron, and shows in the manifest within ~1 min. Debounced
    // (cancel+reschedule) so a burst still batches into one POST. NOT used by the bulk avtrdb
    // crawler (localInsert=false) — that stays on the 2-min loop so a continuous crawl can't
    // keep resetting the debounce and starve the flush.
    private const val QUICK_FLUSH_MS = 15_000L
    @Volatile private var quickFlushJob: Job? = null
    // Paced DB-search seeding from favourites / worn avatars. Each name runs ONE
    // AvatarSearch.searchAll (avtrdb + 2 VRCX mirrors + our catalog), which contributes
    // any file-id-bearing result back — so it grows the catalog with OTHER avatars indexed
    // under that name, with ZERO VRChat REST (searchAll never resolves file ids itself).
    // Drained one name per SEARCH_SEED_PACE_MS so a big favourites list can't rate-limit
    // the DBs; names deduped per session; queue capped.
    private const val SEARCH_SEED_PACE_MS = 6_000L
    private const val SEARCH_SEED_QUEUE_CAP = 1500   // favourite lists can be ~1000
    private const val SEARCH_SEED_MIN_LEN = 3
    private const val SEED_YIELD_MS = 1_000L         // re-check the roster this often while paused
    private const val SEED_MAX_YIELD_MS = 90_000L    // never starve the seed longer than this
    private const val HARVEST_RL_BACKOFF = 5         // consecutive resolve failures => stop (rate-limited)
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
    // Mirror of every avatarId in `map`, for O(1) "do we already have this avatar?" checks
    // (the avtrdb crawler / search harvest dedup by avatarId to skip a VRChat resolve for
    // avatars we already hold). Kept in sync in parseStream/contribute/applyAdminLocal.
    private val avatarIds = ConcurrentHashMap.newKeySet<String>()
    /** True if this `avtr_` id is already in the catalog (any file id). O(1). */
    fun hasAvatarId(avatarId: String): Boolean = avatarIds.contains(avatarId)
    // The catalog's REAL size from /health (post-cutover the local `map` is memory-flat and no
    // longer the whole catalog, so map.size would misleadingly show the old cutover snapshot).
    @Volatile private var shardedCount = -1
    /** Catalog size for debug panels: the sharded total when R2 is live, else the local map. */
    fun catalogCount(): Int = if (r2Serving && shardedCount >= 0) shardedCount else map.size
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Serializes all read-modify-write of the persisted contribution queue so a flush
    // (which drains it) can't race a concurrent contribute (which appends) and lose it.
    private val queueMutex = Mutex()

    // Paced DB-search seed queue: names from favourites / worn avatars, drained one at a
    // time by a slow timer so a big favourites list can't rate-limit the DBs. Deduped so the
    // same name is never re-searched. PERSISTED to disk so a big backlog (a ~1000-favourite
    // list takes ~100 min to drain) resumes across app reopens instead of being lost.
    private val searchSeedQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val seededSearchNames = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    @Volatile private var seedQueueLoaded = false

    private fun loadSeedQueue(context: Context) {
        if (seedQueueLoaded) return
        seedQueueLoaded = true
        try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SEED_QUEUE, "") ?: ""
            raw.split('\n').forEach { n ->
                val name = n.trim()
                if (name.length >= SEARCH_SEED_MIN_LEN && seededSearchNames.add(name.lowercase())) searchSeedQueue.add(name)
            }
        } catch (e: Exception) { Log.w(TAG, "load seed queue failed", e) }
    }

    private fun saveSeedQueue(context: Context) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_SEED_QUEUE, searchSeedQueue.joinToString("\n")).apply()
        } catch (e: Exception) { Log.w(TAG, "save seed queue failed", e) }
    }

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
            // Post-cutover: purge the legacy GitHub-era whole-catalog disk cache (memory-flat).
            // Pre-cutover: load it as before. purge returns true only when R2 is the live backend.
            if (!purgeLegacyCacheIfCutover(app)) loadLocalCache(app)
            loadProcessedFavourites(app)
            loadSeedQueue(app)
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
            var yieldStart = 0L
            while (isActive) {
                // YIELD to the instance roster: its clone-id resolution hits the SAME
                // avtrdb/VRCX mirrors, so running the seed search during the roster's initial
                // load starves it + rate-limits the DBs (the roster takes forever). Pause the
                // seed while the roster is resolving; resume the instant it goes idle. A hard
                // SEED_MAX_YIELD_MS cap lets one name trickle through so a perpetually-busy
                // instance can't starve the seed forever. (Always idle on non-headset builds.)
                if (com.vrca.vrchat.InstanceRosterManager.isResolvingRoster()) {
                    if (yieldStart == 0L) yieldStart = System.currentTimeMillis()
                    if (System.currentTimeMillis() - yieldStart < SEED_MAX_YIELD_MS) {
                        if (searchSeedQueue.isNotEmpty())
                            lastSeedSearch = "paused — roster loading (${searchSeedQueue.size} queued)"
                        delay(SEED_YIELD_MS); continue
                    }
                }
                yieldStart = 0L
                val name = searchSeedQueue.poll()
                if (name == null) { delay(SEARCH_SEED_PACE_MS); continue }
                runCatching { AvatarSearch.searchAll(app, name) }
                saveSeedQueue(app)   // persist the shrunk queue so a reopen resumes here
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
        for (fid in removes) map.remove(fid)?.let { avatarIds.remove(it.avatarId) }
        for (e in upserts) { map[e.fileId] = e.copy(checked = now); avatarIds.add(e.avatarId) }
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

    // ---- sharded reads (R2, learned from /health) ----------------------------
    // The clone path can resolve a worn avatar by fetching ONLY its shard from the R2
    // catalog (edge-cached CDN GET), instead of relying on this device's up-to-~30-min
    // whole-file map. This is what keeps memory flat + clone-resolution fresh at scale.
    //
    // DORMANT until R2 is the LIVE write backend: `refreshCatalogBase` only enables shard
    // fetches when /health reports backend=="r2" (post-cutover). Before cutover the shards
    // are a frozen migration snapshot, so we don't waste requests on them — lookupSharded
    // is a no-op and the existing whole-map + DB-stack paths are unchanged.
    private const val KEY_CATALOG_BASE = "catalog_base" // persisted for cold-start recovery
    private const val KEY_R2 = "r2_serving"
    private const val HEALTH_TTL_MS = 5 * 60_000L
    private const val SHARD_LRU_MAX = 48                 // bounded; evicted on instance leave + overflow

    @Volatile private var catalogBase: String? = null    // e.g. https://cdn.gremlininc.app
    @Volatile private var r2Serving = false              // true only when R2 is the live backend
    @Volatile private var catalogBaseLoaded = false
    @Volatile private var lastHealthMs = 0L

    // Memory-only LRU of recently-fetched shards (prefix -> fileId -> Entry). Access-ordered
    // so the least-recently-used shard falls off past the cap. Cleared on instance leave.
    private val shardLru = object : LinkedHashMap<String, Map<String, Entry>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, Entry>>): Boolean = size > SHARD_LRU_MAX
    }
    // Single-flight: a roster resolves many members at once and several can share a shard;
    // coalesce concurrent fetches of the same prefix into one request.
    private val shardFetches = ConcurrentHashMap<String, Deferred<Map<String, Entry>?>>()

    /** Resolve a worn avatar by its image file id from the R2 shard (post-cutover). Checks
     *  the local map + shard LRU first (both instant), else one edge-cached shard GET.
     *  Returns null when R2 isn't the live backend yet, on a miss, or on any error — so the
     *  caller falls through to its existing paths. Image-file-id-keyed, so a hit is exact
     *  (satisfies the "only an image-confirmed match" resolver invariant by construction). */
    suspend fun lookupSharded(context: Context, fileId: String?): Entry? {
        if (fileId == null || !FILE_RE.matches(fileId)) return null
        map[fileId]?.let { return it }                    // own/whole-map (instant)
        ensureCatalogBase(context)
        if (!r2Serving) return null                       // dormant pre-cutover
        val base = catalogBase ?: return null
        val prefix = fileId.substring(5, 8).lowercase()
        synchronized(shardLru) { shardLru[prefix] }?.let { return it[fileId] }
        val shard = fetchShardSingleFlight(base, prefix) ?: return null
        synchronized(shardLru) { shardLru[prefix] = shard }
        return shard[fileId]
    }

    /** Drop the in-memory shard cache — called on instance leave (presence-scoped
     *  eviction), alongside the roster's own cache clears. The persisted contribution
     *  queue + catalogBase are untouched (separate stores). */
    fun evictShardCache() { synchronized(shardLru) { shardLru.clear() } }

    // ---- admin shard-walk source (memory-flat bots, no whole-master grab) -----
    /** Fetch ONE lookup shard's entries directly from R2 (admin catalog sweep). No LRU/no
     *  presence eviction — the sweep walks shards one at a time so memory stays flat at any
     *  catalog size. Returns null if R2 serving isn't known yet or the read fails. */
    suspend fun fetchCatalogShard(context: Context, prefix: String): List<Entry>? {
        ensureCatalogBase(context)
        val base = catalogBase ?: return null
        val shard = kotlinx.coroutines.withContext(Dispatchers.IO) { fetchShard(base, prefix) } ?: return emptyList()
        return shard.values.toList()
    }

    /** Read the tiny `_manifest.json` (entry/unfilled counts the rebuild Action writes) for
     *  the admin Bots-tab backlog display — no whole-catalog scan. */
    suspend fun fetchManifest(context: Context): JSONObject? {
        ensureCatalogBase(context)
        val base = catalogBase ?: return null
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$base/_manifest.json").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                    connectTimeout = 10_000; readTimeout = 10_000
                }
                if (conn.responseCode != 200) null else JSONObject(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
        }
    }

    /** Read `_worklist.json` (the shard prefixes with fill/stale work the rebuild Action
     *  publishes) so the bots walk ONLY those shards. Returns (fillPrefixes, stalePrefixes),
     *  or null on failure (the sweep then falls back to a blind walk). */
    suspend fun fetchWorklist(context: Context): Pair<List<String>, List<String>>? {
        ensureCatalogBase(context)
        val base = catalogBase ?: return null
        return kotlinx.coroutines.withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$base/_worklist.json").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                    connectTimeout = 10_000; readTimeout = 10_000
                }
                if (conn.responseCode != 200) return@withContext null
                val o = JSONObject(conn.inputStream.bufferedReader().readText())
                fun arr(k: String): List<String> = o.optJSONArray(k)?.let { a ->
                    (0 until a.length()).mapNotNull { a.optString(it, "").takeIf { s -> s.length == 3 } }
                } ?: emptyList()
                arr("fill") to arr("stale")
            } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
        }
    }

    /** True once the sharded catalog is the live source — the sweep uses the shard-walk
     *  (memory-flat) instead of the whole-master read. Same signal as search. */
    fun shardWalkLive(): Boolean = r2Serving

    // ---- sharded SEARCH (token index + fragment store) -----------------------
    // Search is served by two R2 object types (built by the rebuild job from the shards):
    //   index/<3hex>.json      = { v, t: { "<token>": ["avtr_..", ...] } }   (token -> ids)
    //   fragments/<3hex>.json  = { v, e: { "avtr_..": { f, n, au, ai, p, pf } } } (id -> summary)
    // A query fetches the buckets for its tokens (AND-intersect the id lists), then the
    // fragments for the top ids — a handful of edge-cached GETs, never the whole catalog.
    // Bucket keys are computed IDENTICALLY here and in the rebuild job:
    //   index token bucket   = (token.hashCode() & 0xfff)   -> 3 hex   (JS replicates hashCode)
    //   fragment id bucket   = the 3 hex after "avtr_"                  (even, matches shards)
    private const val SEARCH_CACHE_MAX = 96
    private val searchCache = object : LinkedHashMap<String, JSONObject>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, JSONObject>): Boolean = size > SEARCH_CACHE_MAX
    }

    /** True when the sharded search index is the live source (post-cutover). Callers use
     *  the whole-map [searchByName] when this is false (pre-cutover / R2 off). */
    fun r2SearchActive(context: Context): Boolean { ensureCatalogBase(context); return r2Serving }

    /** Is this avatar id already in the catalog? Checked against the sharded avatar-id presence
     *  index (`avtr/<prefix>.json`, built by the rebuild Action) — the memory-flat replacement
     *  for the whole-map [hasAvatarId], so the crawler can skip a VRChat resolve for avatars we
     *  already have without holding the catalog. Cheap + edge-cached. Only fresh to the last
     *  rebuild (~20 min), so the crawler pairs it with a session-set for newly-added ids. */
    suspend fun isAvatarKnownSharded(context: Context, avatarId: String): Boolean {
        if (!avatarId.startsWith("avtr_") || avatarId.length < 8) return false
        ensureCatalogBase(context)
        if (!r2Serving) return false
        val base = catalogBase ?: return false
        val obj = fetchSearchJson("$base/avtr/${avatarId.substring(5, 8).lowercase()}.json") ?: return false
        val arr = obj.optJSONArray("ids") ?: return false
        for (i in 0 until arr.length()) if (arr.optString(i) == avatarId) return true
        return false
    }

    /** Context-free read of the same flag (for the sync search entry points that don't
     *  carry a Context). False until /health has been learned once, so it safely defaults
     *  to the whole-map path on a cold start. */
    fun isR2SearchLive(): Boolean = r2Serving

    private fun indexBucketOf(token: String): String = (token.hashCode() and 0xfff).toString(16).padStart(3, '0')
    private fun fragBucketOf(avatarId: String): String =
        if (avatarId.length >= 8) avatarId.substring(5, 8).lowercase() else "000"

    /**
     * Search the sharded index (token buckets + fragments). Tokenize the query, AND-intersect
     * the token posting lists, fetch the fragments for the survivors, rank (name > author >
     * desc-absent), return as [Entry]s. Empty when R2 search isn't live or nothing matches —
     * the caller then keeps its existing behaviour (whole-map / mirrors).
     */
    suspend fun searchSharded(context: Context, query: String, limit: Int = 60): List<Entry> = coroutineScope {
        ensureCatalogBase(context)
        if (!r2Serving) return@coroutineScope emptyList()
        val base = catalogBase ?: return@coroutineScope emptyList()
        val ql = query.trim().lowercase()
        val tokens = ql.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct()
        if (tokens.isEmpty()) return@coroutineScope emptyList()
        // Fetch every token's posting list IN PARALLEL, then AND-intersect.
        val postings = tokens.map { t -> async { fetchTokenIds(base, t) } }.awaitAll()
        var acc: MutableSet<String>? = null
        for (ids in postings) {
            if (ids.isEmpty()) return@coroutineScope emptyList()   // AND — a token with no postings kills it
            acc = if (acc == null) ids.toMutableSet() else acc.apply { retainAll(ids) }
            if (acc.isEmpty()) return@coroutineScope emptyList()
        }
        // Cap candidates, then fetch their fragment buckets IN PARALLEL (was one-at-a-time =
        // N network round-trips = "takes ages for 40 avatars"). Grouped by bucket so shared
        // buckets are fetched once.
        val idList = (acc ?: return@coroutineScope emptyList()).toList().take(100)
        val byBucket = idList.groupBy { fragBucketOf(it) }
        val fetched = byBucket.keys.map { b -> async { b to (fetchFragments(base, b) ?: emptyMap()) } }
            .awaitAll().toMap()
        val out = ArrayList<Entry>(idList.size)
        for ((bucket, ids) in byBucket) { val frags = fetched[bucket] ?: continue; for (id in ids) frags[id]?.let { out.add(it) } }
        out.sortedByDescending { e ->
            val name = e.name.lowercase(); val author = e.author.lowercase()
            var s = 0
            for (t in tokens) s += when { name.contains(t) -> 5; author.contains(t) -> 2; else -> 1 }
            if (name == ql) s += 20 else if (name.startsWith(tokens.first())) s += 3
            s
        }.distinctBy { it.avatarId }.take(limit)
    }

    // ---- PAGED search (Google-style pages, infinite avatars) ------------------
    // A query's AND-intersected candidate id list is computed ONCE (parallel token fetch)
    // and cached per-query; each page then fetches ONLY that page's fragment buckets. Because
    // the full candidate count is known up-front, `hasMore` is exact (no need to prefetch a
    // page to discover the end) — the UI still prefetches page+1 purely for latency. The
    // candidate order is the index posting order (rank-ordered by the rebuild job), so pages
    // are stable across a session. Evicted on tab-away / new search / close via evictSearchCache.
    data class SearchPage(
        val results: List<Entry>, val page: Int, val pageSize: Int, val total: Int, val hasMore: Boolean,
    )

    private const val CANDIDATE_CACHE_MAX = 8
    private val candidateCache = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean = size > CANDIDATE_CACHE_MAX
    }

    private fun queryKey(query: String): String = query.trim().lowercase()

    /** Compute (or reuse) the ordered AND-intersected candidate id list for a query. The order
     *  is preserved from the first (rarest) token's posting list so pages don't shuffle. */
    private suspend fun candidatesFor(base: String, query: String): List<String> = coroutineScope {
        val key = queryKey(query)
        synchronized(candidateCache) { candidateCache[key] }?.let { return@coroutineScope it }
        val tokens = key.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }.distinct()
        if (tokens.isEmpty()) return@coroutineScope emptyList<String>().also {
            synchronized(candidateCache) { candidateCache[key] = it }
        }
        val postings = tokens.map { t -> async { fetchTokenIds(base, t) } }.awaitAll()
        // Order-preserving intersection: keep the first token's order, drop ids missing from any other.
        val result: List<String> = run {
            if (postings.any { it.isEmpty() }) return@run emptyList()
            val ordered = postings[0]
            if (postings.size == 1) ordered
            else {
                val others = postings.drop(1).map { it.toHashSet() }
                ordered.filter { id -> others.all { it.contains(id) } }
            }
        }
        synchronized(candidateCache) { candidateCache[key] = result }
        result
    }

    /**
     * One page of sharded search results (page is 0-based). Fetches ONLY this page's fragment
     * buckets, so cost is bounded regardless of how many avatars match — the "infinite avatars"
     * path. `hasMore` is exact (candidate count known). Empty page when R2 search isn't live.
     */
    suspend fun searchShardedPage(context: Context, query: String, page: Int, pageSize: Int = 20): SearchPage = coroutineScope {
        ensureCatalogBase(context)
        if (!r2Serving) return@coroutineScope SearchPage(emptyList(), page, pageSize, 0, false)
        val base = catalogBase ?: return@coroutineScope SearchPage(emptyList(), page, pageSize, 0, false)
        val candidates = candidatesFor(base, query)
        val total = candidates.size
        val from = (page * pageSize).coerceAtLeast(0)
        if (from >= total) return@coroutineScope SearchPage(emptyList(), page, pageSize, total, false)
        val slice = candidates.subList(from, minOf(from + pageSize, total))
        val byBucket = slice.groupBy { fragBucketOf(it) }
        val fetched = byBucket.keys.map { b -> async { b to (fetchFragments(base, b) ?: emptyMap()) } }
            .awaitAll().toMap()
        val out = ArrayList<Entry>(slice.size)
        for (id in slice) { val frags = fetched[fragBucketOf(id)] ?: continue; frags[id]?.let { out.add(it) } }
        SearchPage(out, page, pageSize, total, from + pageSize < total)
    }

    /** Drop the sharded-search caches (token/fragment JSON + per-query candidate lists). Called
     *  on tab-away from VRChat / a new search / app close so a query's shards don't linger. */
    fun evictSearchCache() {
        synchronized(searchCache) { searchCache.clear() }
        synchronized(candidateCache) { candidateCache.clear() }
    }

    private suspend fun fetchTokenIds(base: String, token: String): List<String> {
        val obj = fetchSearchJson("$base/index/${indexBucketOf(token)}.json") ?: return emptyList()
        val arr = obj.optJSONObject("t")?.optJSONArray(token) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.startsWith("avtr_") } }
    }

    private suspend fun fetchFragments(base: String, bucket: String): Map<String, Entry>? {
        val obj = fetchSearchJson("$base/fragments/$bucket.json") ?: return null
        val e = obj.optJSONObject("e") ?: return emptyMap()
        val out = HashMap<String, Entry>()
        val keys = e.keys()
        while (keys.hasNext()) {
            val id = keys.next(); if (!id.startsWith("avtr_")) continue
            val o = e.optJSONObject(id) ?: continue
            val mask = o.optInt("p", 0)
            val plats = buildList { if (mask and 1 != 0) add("PC"); if (mask and 2 != 0) add("Quest"); if (mask and 4 != 0) add("iOS") }
            val pf = o.optJSONObject("pf")
            out[id] = Entry(
                o.optString("f", ""), id, o.optString("n", ""), o.optString("au", ""), o.optString("ai", ""),
                plats, 0L, "",
                pf?.optInt("pc", 5) ?: 5, pf?.optInt("q", 5) ?: 5, pf?.optInt("i", 5) ?: 5, filled = true
            )
        }
        return out
    }

    /** GET a small index/fragment JSON object, memory-cached (bounded, size-evicted — search
     *  results aren't instance-scoped, so this is a plain LRU, not presence-evicted). */
    private suspend fun fetchSearchJson(url: String): JSONObject? {
        synchronized(searchCache) { searchCache[url] }?.let { return it }
        val obj = kotlinx.coroutines.withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                    connectTimeout = 10_000; readTimeout = 10_000
                }
                if (conn.responseCode != 200) null else JSONObject(conn.inputStream.bufferedReader().readText())
            } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
        } ?: return null
        synchronized(searchCache) { searchCache[url] = obj }
        return obj
    }

    private suspend fun fetchShardSingleFlight(base: String, prefix: String): Map<String, Entry>? {
        val deferred = shardFetches.computeIfAbsent(prefix) { scope.async { fetchShard(base, prefix) } }
        return try { deferred.await() } finally { shardFetches.remove(prefix, deferred) }
    }

    private fun fetchShard(base: String, prefix: String): Map<String, Entry>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$base/shard/$prefix.json").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != 200) return null      // 404 = empty prefix; treat as miss
            parseShard(conn.inputStream.bufferedReader().readText())
        } catch (e: Exception) { null } finally { runCatching { conn?.disconnect() } }
    }

    private fun parseShard(text: String): Map<String, Entry> {
        val out = HashMap<String, Entry>()
        val e = JSONObject(text).optJSONObject("e") ?: return out
        val keys = e.keys()
        while (keys.hasNext()) {
            val fid = keys.next()
            val o = e.optJSONObject(fid) ?: continue
            val id = o.optString("id", "")
            if (!id.startsWith("avtr_")) continue
            val plats = o.optJSONArray("platforms")?.let { pa ->
                (0 until pa.length()).mapNotNull { pa.optString(it, "").takeIf { s -> s.isNotBlank() } }
            } ?: emptyList()
            out[fid] = Entry(
                fid, id, o.optString("name", ""), o.optString("author", ""), o.optString("authorId", ""),
                plats, o.optLong("checked", o.optLong("added", 0L)),
                o.optString("desc", o.optString("description", "")),
                o.optInt("perfPc", 5), o.optInt("perfQuest", 5), o.optInt("perfIos", 5),
                o.optBoolean("filled", false)
            )
        }
        return out
    }

    /** Learn `catalogBase` + whether R2 is the live backend from /health (TTL'd, off-thread).
     *  Loads persisted values first so a cold start can resolve shards immediately, before
     *  /health responds (crash-recovery). */
    private fun ensureCatalogBase(context: Context) {
        if (!catalogBaseLoaded) {
            catalogBaseLoaded = true
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            catalogBase = prefs.getString(KEY_CATALOG_BASE, null)
            r2Serving = prefs.getBoolean(KEY_R2, false)
        }
        val now = System.currentTimeMillis()
        if (now - lastHealthMs < HEALTH_TTL_MS) return
        lastHealthMs = now
        scope.launch { refreshCatalogBase(context.applicationContext) }
    }

    private fun refreshCatalogBase(context: Context) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$WORKER_URL/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; setRequestProperty("User-Agent", "VRC-A")
                connectTimeout = 10_000; readTimeout = 10_000
            }
            if (conn.responseCode != 200) return
            val j = JSONObject(conn.inputStream.bufferedReader().readText())
            val base = j.optString("catalogBase", "").takeIf { it.startsWith("https://") } ?: return
            // Only trust shards once R2 is the live write backend — before cutover they're a
            // frozen snapshot, so we stay dormant (no wasted requests).
            val r2 = j.optBoolean("r2", false) && j.optString("backend", "") == "r2"
            catalogBase = base
            r2Serving = r2
            j.optInt("entries", -1).takeIf { it >= 0 }?.let { shardedCount = it }   // real catalog size for the debug panel
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_CATALOG_BASE, base).putBoolean(KEY_R2, r2).apply()
        } catch (e: Exception) { /* keep last-known */ } finally { runCatching { conn?.disconnect() } }
    }

    // ---- contribute / report -------------------------------------------------

    /** Queue a newly-learned mapping and try to send it. No-op if we already have
     *  this file id (locally known = already in the global file or queued). */
    fun contribute(
        context: Context, fileId: String, avatarId: String,
        name: String, author: String, authorId: String = "", platforms: List<String> = emptyList(),
        description: String = "", localInsert: Boolean = true
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
        // extra KV cost (this is a purely in-memory local add). BULK contributors (the
        // admin avtrdb crawler — thousands of OTHER avatars) pass localInsert=false so
        // they never bloat the local map / break the post-cutover memory-flat model.
        if (localInsert) {
            map[fileId] = Entry(fileId, avatarId, name, author, authorId, platforms,
                System.currentTimeMillis(), description, filled = false)
            avatarIds.add(avatarId)
        }
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
        // User contributions get a debounced quick-flush so they reach the Worker in ~15s
        // (then the 1-min cron merges them) instead of waiting up to 2 min for the periodic
        // loop. Bulk crawler contributions (localInsert=false) skip this and ride the 2-min loop.
        if (localInsert) scheduleQuickFlush(app)
        return true
    }

    /** Debounced quick-flush of the contribution queue (cancel+reschedule), so a burst of
     *  user contributions batches into one POST ~[QUICK_FLUSH_MS] after the last one. */
    private fun scheduleQuickFlush(context: Context) {
        val app = context.applicationContext
        quickFlushJob?.cancel()
        quickFlushJob = scope.launch { delay(QUICK_FLUSH_MS); flushQueue(app) }
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
            if (f.exists()) parseFile(f)   // STREAMING — never reads the whole file into memory
        } catch (e: Throwable) { Log.w(TAG, "cache load failed", e) }
    }

    /** Post-cutover the app is MEMORY-FLAT (clone via lookupSharded, search via searchSharded),
     *  so the legacy whole-catalog disk cache from the GitHub big-file era is dead weight — it's
     *  ~25 MB on disk, loads ~80k rows into RAM (defeats memory-flat + risks the Quest OOM), and
     *  skews contribute()'s dedup + the debug count. Delete the file + clear the in-memory map so
     *  it never sticks. Own new avatars still seed `map` live via contribute(localInsert=true).
     *  Returns true when it purged (R2 live), false when it left the pre-cutover cache in place. */
    private fun purgeLegacyCacheIfCutover(context: Context): Boolean {
        ensureCatalogBase(context)
        if (!r2Serving) return false
        runCatching { File(context.filesDir, CACHE_FILE).delete() }
        map.clear(); avatarIds.clear()
        return true
    }

    private fun refresh(context: Context, cacheBust: String? = null) {
        ensureCatalogBase(context)   // keep r2Serving current for the source decision below
        // POST-CUTOVER, NEITHER build holds the whole catalog. The public build resolves clone
        // via lookupSharded + search via searchSharded; the ADMIN bots walk shards directly
        // (AvatarCatalogSweep). So skip the ~25 MB whole-file pull entirely — memory-flat at
        // any catalog size. (contribute() still seeds own new avatars into `map` for instant
        // local visibility; the map just never holds the whole catalog.)
        if (r2Serving) return
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
        // Pre-cutover only (post-cutover returned above): freshness poll reads the KV /db,
        // otherwise the GitHub CDN with an ETag.
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
                    // Stream the body straight to a temp file (NEVER hold the whole ~20 MB+
                    // response as one String — that whole-file read is what OOM'd the Quest's
                    // ~268 MB heap on boot), then STREAM-PARSE it with JsonReader.
                    val tmp = File(context.filesDir, "$CACHE_FILE.tmp")
                    conn.inputStream.use { input ->
                        tmp.outputStream().buffered(64 * 1024).use { out -> input.copyTo(out, 64 * 1024) }
                    }
                    val applied = parseFile(tmp)
                    if (applied >= 0) {
                        // Good parse — promote tmp to the cache file atomically.
                        val cache = File(context.filesDir, CACHE_FILE)
                        if (cache.exists()) cache.delete()
                        if (!tmp.renameTo(cache)) { runCatching { tmp.copyTo(cache, overwrite = true) }; tmp.delete() }
                        conn.getHeaderField("ETag")?.let { prefs.edit().putString(KEY_ETAG, it).apply() }
                        lastPull = "pulled ${map.size} at ${nowShort()}"
                    } else {
                        // Empty/bad read — keep the existing cache + catalog.
                        tmp.delete()
                        lastPull = "parse skipped (kept ${map.size}) at ${nowShort()}"
                    }
                }
                else -> lastPull = "http ${conn.responseCode} at ${nowShort()} ($rawUrl)"
            }
        } catch (e: Throwable) {
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

    /**
     * STREAMING parse of the catalog file. Returns the number of entries applied to
     * `map` (>= 0), or -1 if it declined to swap (empty/bad read over a populated
     * catalog, or a parse error) so the caller keeps the existing cache.
     *
     * Memory-bounded on purpose: the file is read through Android's pull-based
     * [JsonReader], so the whole ~20 MB+ document is NEVER materialised as a single
     * String or a full `JSONObject` tree. Peak heap is roughly (result map + one
     * entry at a time), instead of (file String + full JSON tree + map) — the old
     * whole-file read is what OOM-crashed the Quest 3's ~268 MB growth-limit heap on
     * every boot. Catches `Throwable` (incl. OutOfMemoryError) so a pathological file
     * degrades to "keep the old catalog" rather than crashing the app.
     */
    private fun parseFile(file: File): Int = try {
        file.bufferedReader().use { br -> parseStream(br) }
    } catch (e: Throwable) { Log.w(TAG, "parse failed", e); -1 }

    private fun parseStream(reader: Reader): Int {
        val fresh = HashMap<String, Entry>(1 shl 16)
        val jr = JsonReader(reader)
        jr.isLenient = true
        jr.beginObject()
        while (jr.hasNext()) {
            if (jr.nextName() == "avatars" && jr.peek() == JsonToken.BEGIN_OBJECT) {
                jr.beginObject()
                while (jr.hasNext()) {
                    val fileId = jr.nextName()
                    val e = readEntry(jr, fileId)
                    // Merge local progress (filled/checked ahead of the file) as we go, so
                    // we never hold a third full map just to merge.
                    if (e != null) fresh[fileId] = mergeWithLocal(e, map[fileId])
                }
                jr.endObject()
            } else jr.skipValue()
        }
        jr.endObject()
        // SAFETY: never replace a populated catalog with an empty parse (a blank /db
        // before the first flush, a truncated read, etc.).
        if (fresh.isEmpty() && map.isNotEmpty()) return -1
        map.clear(); map.putAll(fresh)
        avatarIds.clear(); fresh.values.forEach { avatarIds.add(it.avatarId) }
        return fresh.size
    }

    /** Read one `"file_...": { ... }` catalog entry from the stream. Consumes exactly
     *  the value token for [fileId]. Returns null (still consuming the value) for a
     *  malformed / non-object / non-avatar entry. */
    private fun readEntry(jr: JsonReader, fileId: String): Entry? {
        if (jr.peek() != JsonToken.BEGIN_OBJECT) { jr.skipValue(); return null }
        var id = ""; var name = ""; var author = ""; var authorId = ""; var desc = ""
        var checked = 0L; var added = 0L
        var perfPc = 5; var perfQuest = 5; var perfIos = 5
        var filled = false
        val plats = ArrayList<String>(3)
        jr.beginObject()
        while (jr.hasNext()) {
            when (jr.nextName()) {
                "id" -> id = jr.readStringSafe()
                "name" -> name = jr.readStringSafe()
                "author" -> author = jr.readStringSafe()
                "authorId" -> authorId = jr.readStringSafe()
                "desc", "description" -> { val d = jr.readStringSafe(); if (d.isNotBlank()) desc = d }
                "checked" -> checked = jr.readLongSafe()
                "added" -> added = jr.readLongSafe()
                "perfPc" -> perfPc = jr.readIntSafe(5)
                "perfQuest" -> perfQuest = jr.readIntSafe(5)
                "perfIos" -> perfIos = jr.readIntSafe(5)
                "filled" -> filled = jr.readBoolSafe()
                "platforms" -> {
                    if (jr.peek() == JsonToken.BEGIN_ARRAY) {
                        jr.beginArray()
                        while (jr.hasNext()) { val s = jr.readStringSafe(); if (s.isNotBlank()) plats.add(s) }
                        jr.endArray()
                    } else jr.skipValue()
                }
                else -> jr.skipValue()
            }
        }
        jr.endObject()
        if (!FILE_RE.matches(fileId)) return null
        if (!id.startsWith("avtr_")) return null
        return Entry(
            fileId, id, name, author, authorId, plats,
            if (checked != 0L) checked else added, desc, perfPc, perfQuest, perfIos, filled
        )
    }

    // Defensive token readers — tolerate null / type-mismatched values without throwing
    // (a bad field degrades to a default instead of aborting the whole streaming parse).
    private fun JsonReader.readStringSafe(): String = when (peek()) {
        JsonToken.NULL -> { nextNull(); "" }
        JsonToken.BOOLEAN -> nextBoolean().toString()
        else -> try { nextString() } catch (e: Exception) { runCatching { skipValue() }; "" }
    }
    private fun JsonReader.readLongSafe(): Long = when (peek()) {
        JsonToken.NULL -> { nextNull(); 0L }
        JsonToken.STRING -> nextString().toLongOrNull() ?: 0L
        else -> try { nextLong() } catch (e: Exception) { runCatching { skipValue() }; 0L }
    }
    private fun JsonReader.readIntSafe(def: Int): Int = when (peek()) {
        JsonToken.NULL -> { nextNull(); def }
        JsonToken.STRING -> nextString().toIntOrNull() ?: def
        else -> try { nextInt() } catch (e: Exception) { runCatching { skipValue() }; def }
    }
    private fun JsonReader.readBoolSafe(): Boolean = when (peek()) {
        JsonToken.NULL -> { nextNull(); false }
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.STRING -> nextString().equals("true", true)
        else -> { runCatching { skipValue() }; false }
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
            // NO count cap — an avtrdb query matches name/author only (no bios), so a term's
            // result set is bounded; resolve them all. Dedup by avatar id against the catalog
            // FIRST (zero VRChat call for ones we already have), and back off if VRChat starts
            // rate-limiting (HARVEST_RL_BACKOFF consecutive failures) so a broad search can't
            // hammer the user's session.
            // Dedup "already in catalog" WITHOUT a VRChat call: post-cutover use the sharded
            // avatar-id index (memory-flat), pre-cutover the local map. Without this, a purged
            // map would make every result hit VRChat REST → rate-limit → fewer contributions.
            val knownIds = if (r2Serving) HashSet() else HashSet<String>(map.size).apply { for (e in map.values) add(e.avatarId) }
            var nulls = 0
            for (r in results) {
                if (r.imageFileId != null) continue           // already contributed in searchAll
                val known = if (r2Serving) isAvatarKnownSharded(app, r.id) else knownIds.contains(r.id)
                if (known) continue                            // already in catalog — no VRChat call
                val fid = try { VrchatAuthManager.avatarCatalogEntry(app, r.id)?.fileId }
                    catch (e: Exception) { null }
                if (fid == null) { if (++nulls >= HARVEST_RL_BACKOFF) break; delay(600); continue }
                nulls = 0
                if (map.containsKey(fid)) continue
                contribute(app, fid, r.id, r.name, r.author, r.authorId, r.platforms)
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
                if (r2Serving && isAvatarKnownSharded(app, id)) continue   // known — skip the VRChat resolve
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
            saveSeedQueue(context)   // persist the names this sweep queued (favourites seed the queue)
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
