package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cheap conversation understanding without re-reading 100 messages every turn.
 *
 *  - A per-channel **rolling summary** (in-memory) is the live "what's going on" line the reply
 *    tail keeps re-writing, so the bot always has the gist for pennies.
 *  - When a channel goes quiet for [DiscordBotLimits.CONVO_GAP_MS] the active summary is sealed
 *    into a persisted **topic archive** (keyword-indexed). If someone answers a dormant message
 *    hours later, [reviveFor] pulls the matching topic back so the convo can pick up where it left
 *    off — the "revive a dead conversation" feature.
 *
 * Stores are unbounded by design (text is tiny); only per-prompt INJECTION is retrieval-limited.
 * Plain SharedPreferences (`vrca_discord_convo`), one JSON array of topics per `t_<channelId>` key.
 */
object ConversationStore {
    private const val PREFS = "vrca_discord_convo"
    private const val KEY_PREFIX = "t_"

    /** In-memory live summary per channel + the wall-clock of the last message folded into it. */
    private val liveSummary = HashMap<String, String>()
    private val lastMsgAt = HashMap<String, Long>()

    data class Topic(val summary: String, val closedMs: Long, val keywords: List<String>)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyOf(ch: String) = KEY_PREFIX + ch

    /** The live rolling summary for a channel (blank until a reply/observer writes one). */
    @Synchronized
    fun summary(channelId: String): String = liveSummary[channelId].orEmpty()

    /**
     * Fold a fresh model-written summary into the channel. If the channel had been quiet past the
     * gap, the OLD summary is first archived as a revivable topic, then the new one becomes live.
     */
    @Synchronized
    fun updateSummary(ctx: Context, channelId: String, newSummary: String, nowMs: Long) {
        val s = newSummary.trim().take(DiscordBotLimits.SUMMARY_MAX_CHARS)
        if (s.isBlank()) { lastMsgAt[channelId] = nowMs; return }
        val prev = liveSummary[channelId]
        val quietFor = nowMs - (lastMsgAt[channelId] ?: nowMs)
        if (!prev.isNullOrBlank() && quietFor >= DiscordBotLimits.CONVO_GAP_MS && prev != s) {
            archive(ctx, channelId, prev, nowMs)
        }
        liveSummary[channelId] = s
        lastMsgAt[channelId] = nowMs
    }

    /** Mark activity (used to detect the quiet gap even when no summary was produced). */
    @Synchronized
    fun touch(channelId: String, nowMs: Long) { lastMsgAt[channelId] = nowMs }

    /** Seal the current live summary into the archive (e.g. on the observer noticing a lull). */
    @Synchronized
    fun sealIfDormant(ctx: Context, channelId: String, nowMs: Long) {
        val prev = liveSummary[channelId] ?: return
        val quietFor = nowMs - (lastMsgAt[channelId] ?: nowMs)
        if (prev.isNotBlank() && quietFor >= DiscordBotLimits.CONVO_GAP_MS) {
            archive(ctx, channelId, prev, nowMs)
            liveSummary.remove(channelId)
        }
    }

    private fun archive(ctx: Context, channelId: String, summary: String, nowMs: Long) {
        val list = load(ctx, channelId).toMutableList()
        // Don't archive a near-duplicate of the newest topic.
        if (list.firstOrNull()?.summary?.equals(summary, true) == true) return
        list.add(0, Topic(summary, nowMs, keywordsOf(summary)))
        save(ctx, channelId, list.take(DiscordBotLimits.TOPIC_STORE_MAX))
    }

    /**
     * Pull up to [DiscordBotLimits.TOPIC_RETRIEVE_MAX] archived topics whose keywords overlap the
     * incoming [text] — so a reply to a dormant message revives the right dead thread. Returns the
     * topic summaries (most-relevant first), or empty when nothing meaningfully matches.
     */
    fun reviveFor(ctx: Context, channelId: String, text: String): List<String> {
        val topics = load(ctx, channelId)
        if (topics.isEmpty()) return emptyList()
        val want = keywordsOf(text).toSet()
        if (want.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return topics
            .map { it to it.keywords.count { k -> k in want } }
            .filter { it.second >= 2 }                       // at least two shared keywords
            .sortedByDescending { it.second }
            .take(DiscordBotLimits.TOPIC_RETRIEVE_MAX)
            .map { (t, _) -> val ago = discordRelTime(t.closedMs, now); if (ago.isBlank()) t.summary else "${t.summary} ($ago)" }
    }

    // ── keyword extraction (tiny, dependency-free) ──
    private val STOP = setOf(
        "the","a","an","and","or","but","to","of","in","on","for","with","is","are","was","were",
        "be","this","that","it","its","im","i","you","your","he","she","they","we","me","my","at",
        "so","just","like","lol","ok","yeah","yes","no","do","did","does","not","what","who","how",
        "why","when","if","then","than","cardinal","https","http","link"
    )
    private fun keywordsOf(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase())
            .map { it.value }
            .filter { it.length >= 4 && it !in STOP }
            .distinct()
            .take(12)
            .toList()

    private fun load(ctx: Context, ch: String): List<Topic> {
        return try {
            val raw = prefs(ctx).getString(keyOf(ch), null) ?: return emptyList()
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val sum = o.optString("s").trim(); if (sum.isBlank()) return@mapNotNull null
                Topic(sum, o.optLong("c", 0L),
                    o.optJSONArray("k")?.let { k -> (0 until k.length()).map { k.optString(it) } } ?: keywordsOf(sum))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, ch: String, topics: List<Topic>) {
        val arr = JSONArray()
        topics.forEach { t ->
            arr.put(JSONObject().put("s", t.summary).put("c", t.closedMs).put("k", JSONArray(t.keywords)))
        }
        prefs(ctx).edit().putString(keyOf(ch), arr.toString()).apply()
    }

    // ── admin ──
    fun topicsFor(ctx: Context, ch: String): List<Topic> = load(ctx, ch)
    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply(); synchronized(this) { liveSummary.clear(); lastMsgAt.clear() } }
}
