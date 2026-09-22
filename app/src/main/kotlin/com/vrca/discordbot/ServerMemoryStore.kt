package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rough "how long ago" for a stored timestamp, so injected memories/topics read with real recency
 * (Cardinal won't call a 3-week-old moment "just now"). Blank for unknown/0. Shared across the
 * discordbot stores. Never stored — computed at render time so it can't stale.
 */
internal fun discordRelTime(thenMs: Long, nowMs: Long): String {
    if (thenMs <= 0L) return ""
    val d = nowMs - thenMs
    return when {
        d < 2 * 60_000L -> "just now"
        d < 60 * 60_000L -> "${d / 60_000L}m ago"
        d < 24 * 3_600_000L -> "${d / 3_600_000L}h ago"
        d < 7 * 86_400_000L -> "${d / 86_400_000L}d ago"
        d < 30 * 86_400_000L -> "${d / (7 * 86_400_000L)}w ago"
        else -> "a while back"
    }
}

/**
 * Shared **server culture** — memorable moments, running jokes and inside references the whole
 * server shares, that Cardinal can bring up AND recognise when someone else references them.
 *
 * These are DIFFERENT from [UserMemoryStore] (about a person) and [PersonalityStore.episodes]
 * (about Cardinal himself): a server memory is collective ("the time the raid boss ate everyone",
 * "'skibidi' is the cursed word here"). The reply tail and the observer both propose them; each
 * reference reinforces one so live culture rises and stale one-offs fade.
 *
 * The store is unbounded by design (text is tiny); only per-prompt INJECTION is retrieval-limited
 * ([DiscordBotLimits.EVENT_RETRIEVE_MAX] + the [DiscordBotLimits.CORE_MEMORIES_INJECT] strongest
 * always folded into the digest). Plain SharedPreferences (`vrca_discord_server_mem`).
 */
object ServerMemoryStore {
    private const val PREFS = "vrca_discord_server_mem"
    private const val KEY = "memories"

    data class Memory(
        val text: String,
        val strength: Int,
        val keywords: List<String>,
        val lastMs: Long,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context): List<Memory> {
        return try {
            val raw = prefs(ctx).getString(KEY, null) ?: return emptyList()
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t").trim(); if (t.isBlank()) return@mapNotNull null
                Memory(t, o.optInt("s", 1).coerceIn(1, 20),
                    o.optJSONArray("k")?.let { k -> (0 until k.length()).map { k.optString(it) } } ?: keywordsOf(t),
                    o.optLong("m", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, list: List<Memory>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("t", m.text).put("s", m.strength)
                .put("k", JSONArray(m.keywords)).put("m", m.lastMs))
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /**
     * Record/reinforce a server memory. A near-duplicate (keyword overlap) is REINFORCED instead
     * of duplicated, so the same joke referenced twice rises rather than cluttering. New ones start
     * at strength 2. Called from the reply tail's `event` field and the observer.
     */
    @Synchronized
    fun remember(ctx: Context, text: String, nowMs: Long) {
        val t = text.trim().take(160); if (t.length < 6) return
        val kw = keywordsOf(t)
        val list = load(ctx).toMutableList()
        val hit = list.indexOfFirst { m ->
            m.text.equals(t, true) || kw.isNotEmpty() && m.keywords.count { it in kw } >= 3
        }
        if (hit >= 0) {
            val m = list[hit]
            list[hit] = m.copy(strength = (m.strength + 1).coerceAtMost(20), lastMs = nowMs)
        } else {
            list.add(Memory(t, 2, kw, nowMs))
        }
        // FIFO-by-weakness ceiling: drop the weakest/oldest if we somehow blow the sane cap.
        val trimmed = if (list.size > DiscordBotLimits.SERVER_MEMORY_STORE_MAX)
            list.sortedWith(compareByDescending<Memory> { it.strength }.thenByDescending { it.lastMs })
                .take(DiscordBotLimits.SERVER_MEMORY_STORE_MAX)
        else list
        save(ctx, trimmed)
    }

    /** Reinforce any memory the incoming [text] references (recognising the inside joke). */
    @Synchronized
    fun reinforceReferenced(ctx: Context, text: String, nowMs: Long) {
        val want = keywordsOf(text).toSet()
        if (want.isEmpty()) return
        val list = load(ctx)
        var changed = false
        val out = list.map { m ->
            if (m.keywords.count { it in want } >= 2) { changed = true; m.copy(strength = (m.strength + 1).coerceAtMost(20), lastMs = nowMs) }
            else m
        }
        if (changed) save(ctx, out)
    }

    private fun withAgo(m: Memory, now: Long): String {
        val ago = discordRelTime(m.lastMs, now)
        return if (ago.isBlank()) m.text else "${m.text} ($ago)"
    }

    /** The strongest memories, always folded into the digest so shared culture is ambient. */
    fun core(ctx: Context): List<String> {
        val now = System.currentTimeMillis()
        return load(ctx).sortedByDescending { it.strength }
            .take(DiscordBotLimits.CORE_MEMORIES_INJECT).map { withAgo(it, now) }
    }

    /** Retrieve memories relevant to [text] (keyword overlap), beyond the always-on core. */
    fun retrieveFor(ctx: Context, text: String): List<String> {
        val want = keywordsOf(text).toSet(); if (want.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return load(ctx)
            .map { it to it.keywords.count { k -> k in want } }
            .filter { it.second >= 2 }
            .sortedByDescending { it.second * 10 + it.first.strength }
            .take(DiscordBotLimits.EVENT_RETRIEVE_MAX)
            .map { withAgo(it.first, now) }
    }

    private val STOP = setOf(
        "the","a","an","and","or","but","to","of","in","on","for","with","is","are","was","were",
        "be","this","that","it","its","you","your","they","we","just","like","lol","cardinal","time"
    )
    private fun keywordsOf(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase())
            .map { it.value }.filter { it.length >= 4 && it !in STOP }.distinct().take(12).toList()

    // ── admin ──
    fun list(ctx: Context): List<Memory> = load(ctx).sortedByDescending { it.strength }
    @Synchronized
    fun delete(ctx: Context, text: String) { save(ctx, load(ctx).filterNot { it.text.equals(text, true) }) }
    @Synchronized
    fun teach(ctx: Context, text: String) {
        val t = text.trim(); if (t.isBlank()) return
        val list = load(ctx).filterNot { it.text.equals(t, true) }
        save(ctx, listOf(Memory(t, 20, keywordsOf(t), System.currentTimeMillis())) + list)
    }
    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
}
