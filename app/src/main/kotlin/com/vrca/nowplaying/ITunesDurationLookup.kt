package com.vrca.nowplaying

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Ground-truth song-duration lookup via the free, key-less iTunes Search API
 * (`https://itunes.apple.com/search`). Used to harden YouTube Music ad detection:
 * YT Music reuses the previous/upcoming song's TITLE during an ad but reports a much
 * shorter duration, so knowing the song's REAL length lets us flag an ad even on the
 * very first (pre-roll) ad of a pod — where the device has no long baseline to collapse
 * from yet (the "first ad missed, second works" case).
 *
 * It is best-effort and strictly additive: a lookup only ever RAISES the established
 * length used for the duration-collapse check; if the network fails or no match is found
 * the existing device-duration heuristics stand on their own. Results (including misses,
 * stored as 0) are cached for the process lifetime so a given title is fetched at most
 * once, and in-flight keys are de-duped so the 500ms YT poll can't spawn a request storm.
 */
object ITunesDurationLookup {
    // key -> trackTimeMillis (>0 = real duration, 0 = looked up but no match)
    private val cache = ConcurrentHashMap<String, Long>()
    private val inFlight = Collections.synchronizedSet(HashSet<String>())
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "itunes-duration-lookup").apply { isDaemon = true }
    }

    private fun key(title: String, artist: String): String =
        (title.trim() + "|" + artist.trim()).lowercase()

    /** Returns a real duration (ms) if we've already resolved one, else null. */
    fun cached(title: String, artist: String): Long? {
        val v = cache[key(title, artist)] ?: return null
        return if (v > 0L) v else null
    }

    /**
     * Warm the cache for this title/artist on a background thread. No-op if already
     * cached or in flight. Safe to call every sample.
     */
    fun prefetch(title: String, artist: String) {
        val t = title.trim()
        if (t.isBlank()) return
        val k = key(t, artist)
        if (cache.containsKey(k) || !inFlight.add(k)) return
        executor.execute {
            val ms = try {
                fetch(t, artist)
            } catch (_: Throwable) {
                0L
            }
            cache[k] = ms
            inFlight.remove(k)
        }
    }

    private fun fetch(title: String, artist: String): Long {
        val term = URLEncoder.encode(listOf(artist, title).filter { it.isNotBlank() }
            .joinToString(" ").trim(), "UTF-8")
        if (term.isBlank()) return 0L
        val url = URL("https://itunes.apple.com/search?term=$term&entity=song&limit=1")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (conn.responseCode != 200) return 0L
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val results = JSONObject(body).optJSONArray("results") ?: return 0L
            if (results.length() == 0) return 0L
            return results.getJSONObject(0).optLong("trackTimeMillis", 0L)
        } finally {
            conn.disconnect()
        }
    }
}
