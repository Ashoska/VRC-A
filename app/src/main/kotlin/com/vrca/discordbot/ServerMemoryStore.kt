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
 * Light English suffix stripping so relevance matching treats "events"/"event", "hosts"/"hosted"/
 * "hosting"/"host" as the same word. Used ONLY for matching (both sides stemmed the same way); stored
 * text is never changed.
 */
internal fun discordStem(w: String): String {
    if (w.length > 5 && w.endsWith("ies")) return w.dropLast(3) + "y"
    for (suf in arrayOf("ing", "ed", "es", "s")) {
        if (w.length - suf.length >= 3 && w.endsWith(suf)) return w.dropLast(suf.length)
    }
    return w
}

/**
 * Shared **server culture** — memorable moments, running jokes and inside references the whole
 * server shares, that Cardinal can bring up AND recognise when someone else references them.
 *
 * These are DIFFERENT from [UserMemoryStore] (about a person) and [PersonalityStore.episodes]
 * (about Cardinal himself): a server memory is collective ("the time the raid boss ate everyone",
 * "'skibidi' is the cursed word here"). The reply tail and the observer both propose them; each
 * reference reinforces one (at most once per [DiscordBotLimits.MEMORY_REINFORCE_COOLDOWN_MS]) so live
 * culture rises, and one nobody mentions slowly ranks lower ([DiscordBotLimits.MEMORY_FADE_MS]).
 *
 * The store is unbounded by design (text is tiny); only per-prompt INJECTION is retrieval-limited:
 * a memory is injected only when the conversation touches it ([DiscordBotLimits.EVENT_RETRIEVE_MAX]).
 * Plain SharedPreferences (`vrca_discord_server_mem`).
 */
object ServerMemoryStore {
    private const val PREFS = "vrca_discord_server_mem"
    private const val KEY = "memories"

    data class Memory(
        val text: String,
        val strength: Int,
        val keywords: List<String>,
        val lastMs: Long,          // last time it was referenced/reinforced
        val firstMs: Long = 0L,    // when it was first remembered (0 = unknown, older data)
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
                    o.optLong("m", 0L), o.optLong("f", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, list: List<Memory>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("t", m.text).put("s", m.strength)
                .put("k", JSONArray(m.keywords)).put("m", m.lastMs).put("f", m.firstMs))
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
        val t = text.trim().take(160); if (t.length < 6 || ContentBoundary.hatefulAboutGroup(t)) return
        val kw = keywordsOf(t)
        val list = load(ctx).toMutableList()
        val hit = list.indexOfFirst { m ->
            // Same memory if the texts match, or the new one's words (up to 3 of them) are all already there
            // — so a short restatement ("cursed Halloween") reinforces the full memory instead of duplicating it.
            m.text.equals(t, true) || kw.size >= 2 && m.keywords.count { it in kw } >= minOf(3, kw.size)
        }
        if (hit >= 0) {
            val m = list[hit]
            list[hit] = m.copy(strength = (m.strength + 1).coerceAtMost(20), lastMs = nowMs)
        } else {
            list.add(Memory(t, 2, kw, nowMs, firstMs = nowMs))
        }
        // FIFO-by-weakness ceiling: drop the weakest/oldest if we somehow blow the sane cap.
        val trimmed = if (list.size > DiscordBotLimits.SERVER_MEMORY_STORE_MAX)
            list.sortedWith(compareByDescending<Memory> { effectiveStrength(it, nowMs) }.thenByDescending { it.lastMs })
                .take(DiscordBotLimits.SERVER_MEMORY_STORE_MAX)
        else list
        save(ctx, trimmed)
    }

    /** Reinforce any memory the incoming [text] references (recognising the inside joke) — at most once
     *  per [DiscordBotLimits.MEMORY_REINFORCE_COOLDOWN_MS], so a chat that keeps using the same words
     *  doesn't inflate one memory by +1 per message. */
    @Synchronized
    fun reinforceReferenced(ctx: Context, text: String, nowMs: Long) {
        val want = keywordsOf(text).map { discordStem(it) }.toSet()
        if (want.isEmpty()) return
        val list = load(ctx)
        var changed = false
        val out = list.map { m ->
            if (nowMs - m.lastMs >= DiscordBotLimits.MEMORY_REINFORCE_COOLDOWN_MS &&
                m.keywords.count { discordStem(it) in want } >= 2) {
                changed = true; m.copy(strength = (m.strength + 1).coerceAtMost(20), lastMs = nowMs)
            } else m
        }
        if (changed) save(ctx, out)
    }

    /** Strength, minus a point per [DiscordBotLimits.MEMORY_FADE_MS] since anyone last mentioned it. */
    private fun effectiveStrength(m: Memory, nowMs: Long): Int {
        val last = maxOf(m.lastMs, m.firstMs)
        if (last <= 0L) return m.strength
        return m.strength - ((nowMs - last).coerceAtLeast(0L) / DiscordBotLimits.MEMORY_FADE_MS).toInt()
    }

    /** Age = when it HAPPENED (first remembered), never when it was last mentioned — re-stamping on
     *  every mention made months-old moments read "(just now)". Unknown age → no suffix. */
    private fun withAgo(m: Memory, now: Long): String {
        val ago = discordRelTime(m.firstMs, now)
        return if (ago.isBlank()) m.text else "${m.text} ($ago)"
    }

    /** Retrieve memories relevant to [text] (keyword overlap). */
    fun retrieveFor(ctx: Context, text: String): List<String> = retrieveFor(ctx, keywordsOf(text).toSet(), 2)

    /**
     * Memories relevant to the current moment: at least [minOverlap] (stemmed) keywords shared with
     * [keywords] (the recent conversation). A direct memory question passes minOverlap = 1.
     */
    fun retrieveFor(ctx: Context, keywords: Set<String>, minOverlap: Int): List<String> {
        val want = keywords.map { discordStem(it) }.toSet(); if (want.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return load(ctx)
            .map { m -> m to m.keywords.count { k -> discordStem(k) in want } }
            .filter { it.second >= minOverlap }
            .sortedByDescending { it.second * 10 + effectiveStrength(it.first, now) }
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
