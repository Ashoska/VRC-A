package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cheap conversation understanding without re-reading 100 messages every turn.
 *
 *  - A per-channel **rolling summary** is the live "what's going on" line each learn pass rewrites,
 *    so the bot always has the gist for pennies. It's saved, so a restart doesn't lose it.
 *  - The first message after the channel was quiet for [DiscordBotLimits.CONVO_GAP_MS] starts a new
 *    conversation: the old summary is sealed into a persisted **topic archive** (keyword-indexed)
 *    and the live one starts fresh. If someone brings the old topic up hours later, [reviveFor]
 *    pulls it back so the convo can pick up where it left off.
 *
 * Stores are unbounded by design (text is tiny); only per-prompt INJECTION is retrieval-limited.
 * Plain SharedPreferences (`vrca_discord_convo`): one JSON array of topics per `t_<channelId>` key,
 * the live summary per `live_<channelId>`.
 */
object ConversationStore {
    private const val PREFS = "vrca_discord_convo"
    private const val KEY_PREFIX = "t_"
    private const val LIVE_PREFIX = "live_"

    /** In-memory live summary per channel + the wall-clock of the last message folded into it. */
    private val liveSummary = HashMap<String, String>()
    private val lastMsgAt = HashMap<String, Long>()
    private val summaryAt = HashMap<String, Long>()   // when the live summary was last written
    private val lastPersistAt = HashMap<String, Long>()

    data class Topic(val summary: String, val closedMs: Long, val keywords: List<String>)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyOf(ch: String) = KEY_PREFIX + ch

    /** The live rolling summary for a channel (blank until a reply/observer writes one). */
    @Synchronized
    fun summary(channelId: String): String = liveSummary[channelId].orEmpty()

    /** The live summary only if it was written within [maxAgeMs] — an hours-old summary describes a
     *  PREVIOUS conversation and would mislead the reply (blank otherwise). */
    @Synchronized
    fun freshSummary(channelId: String, nowMs: Long, maxAgeMs: Long): String {
        val s = liveSummary[channelId].orEmpty(); if (s.isBlank()) return ""
        return if (nowMs - (summaryAt[channelId] ?: 0L) <= maxAgeMs) s else ""
    }

    /** Load the saved live summaries (call once when the bot starts). Never overwrites newer state. */
    @Synchronized
    fun restore(ctx: Context) {
        for ((k, v) in prefs(ctx).all) {
            if (!k.startsWith(LIVE_PREFIX) || v !is String) continue
            val ch = k.removePrefix(LIVE_PREFIX)
            if (!liveSummary[ch].isNullOrBlank()) continue
            try {
                val o = JSONObject(v)
                val sum = o.optString("s").trim(); if (sum.isBlank()) continue
                liveSummary[ch] = sum
                summaryAt[ch] = o.optLong("at", 0L)
                lastMsgAt[ch] = o.optLong("last", o.optLong("at", 0L))
            } catch (_: Exception) { }
        }
    }

    private fun persistLive(ctx: Context, channelId: String) {
        val sum = liveSummary[channelId]
        val e = prefs(ctx).edit()
        if (sum.isNullOrBlank()) e.remove(LIVE_PREFIX + channelId)
        else e.putString(LIVE_PREFIX + channelId, JSONObject().put("s", sum)
            .put("at", summaryAt[channelId] ?: 0L).put("last", lastMsgAt[channelId] ?: 0L).toString())
        e.apply()
        lastPersistAt[channelId] = System.currentTimeMillis()
    }

    /** A learn pass wrote a fresh summary of what's going on now. */
    @Synchronized
    fun updateSummary(ctx: Context, channelId: String, newSummary: String, nowMs: Long, speakers: Collection<String> = emptyList()) {
        val fresh = newSummary.trim()
        if (fresh.isBlank()) return
        val s = keepThread(liveSummary[channelId], fresh, speakers).take(DiscordBotLimits.SUMMARY_MAX_CHARS)
        liveSummary[channelId] = s
        summaryAt[channelId] = nowMs
        if (lastMsgAt[channelId] == null) lastMsgAt[channelId] = nowMs
        persistLive(ctx, channelId)
    }

    /**
     * A message arrived. If the channel had been quiet for [DiscordBotLimits.CONVO_GAP_MS], this starts a
     * new conversation: the old summary is archived as a revivable topic first, then the live one clears.
     */
    @Synchronized
    fun touch(ctx: Context, channelId: String, nowMs: Long) {
        val prevAt = lastMsgAt[channelId]
        val prev = liveSummary[channelId]
        lastMsgAt[channelId] = nowMs
        if (!prev.isNullOrBlank() && prevAt != null && nowMs - prevAt >= DiscordBotLimits.CONVO_GAP_MS) {
            archive(ctx, channelId, prev, prevAt)
            liveSummary.remove(channelId); summaryAt.remove(channelId)
            persistLive(ctx, channelId)
            return
        }
        // Keep the saved "last message" time roughly current so a restart still sees the gap.
        if (!prev.isNullOrBlank() && nowMs - (lastPersistAt[channelId] ?: 0L) >= 60_000L) persistLive(ctx, channelId)
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
    /**
     * The same conversation's next learn pass often sees only its last few lines and summarises just
     * those ("finn has noodle arms"), dropping what the conversation is actually about ("alice is moving").
     * If the new summary shares no topic word with the previous one (people's names don't count), keep the
     * previous topic as the headline: "<topic> — now: <latest>". A pass that names the topic again replaces it.
     */
    internal fun keepThread(prev: String?, fresh: String, speakers: Collection<String>): String {
        if (prev.isNullOrBlank()) return fresh
        val headline = prev.substringBefore(NOW_SEP).trim()
        val names = speakers.map { it.lowercase() }.toSet()
        fun topic(t: String) = keywordsOf(t).filter { it !in names && it.removeSuffix("s") !in names }.map { discordStem(it) }.toSet()
        val old = topic(headline)
        if (old.isEmpty() || topic(fresh).any { it in old }) return fresh
        return headline + NOW_SEP + fresh
    }

    private const val NOW_SEP = " — now: "

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
    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        synchronized(this) { liveSummary.clear(); lastMsgAt.clear(); summaryAt.clear(); lastPersistAt.clear() }
    }
}
