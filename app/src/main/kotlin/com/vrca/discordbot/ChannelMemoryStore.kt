package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-channel **running bits / norms** — a channel-scoped sibling of [ServerMemoryStore]. A bit is a
 * recurring joke or convention that belongs to ONE channel ("posting an image in #general gets ribbed
 * as 'gay'", "#help stays on topic"). Learned by the same reply-tail / observer path that learns
 * server culture, but tagged with the channel it happened in, so it's only ever recalled in that
 * channel — #general's image bit never surfaces in #media.
 *
 * The point isn't to fire a bit EVERY time its trigger recurs (that's the robot version) — it's to
 * drop it *occasionally*, the way a person who's made the joke before does. So [pickDeployable] gates
 * an eligible bit behind a per-bit cooldown ([DiscordBotLimits.CHANNEL_BIT_DEPLOY_COOLDOWN_MS]) plus
 * a probability roll ([DiscordBotLimits.CHANNEL_BIT_DEPLOY_CHANCE]) — most triggers pass with no bit,
 * and every now and then Cardinal leans on one.
 *
 * Store is unbounded per channel by design (text is tiny); only INJECTION is limited. Persisted in
 * `vrca_discord_channel_mem`, one JSON array of bits per `b_<channelId>` key.
 */
object ChannelMemoryStore {
    private const val PREFS = "vrca_discord_channel_mem"
    private const val KEY_PREFIX = "b_"

    data class Bit(
        val text: String,
        val strength: Int,
        val keywords: List<String>,
        val lastMs: Long,       // last reinforced/created
        val lastDeployMs: Long, // last time Cardinal actually leaned on it (for the deploy cooldown)
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyOf(ch: String) = KEY_PREFIX + ch

    @Synchronized
    fun load(ctx: Context, channelId: String): List<Bit> {
        return try {
            val raw = prefs(ctx).getString(keyOf(channelId), null) ?: return emptyList()
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t").trim(); if (t.isBlank()) return@mapNotNull null
                Bit(t, o.optInt("s", 1).coerceIn(1, 20),
                    o.optJSONArray("k")?.let { k -> (0 until k.length()).map { k.optString(it) } } ?: keywordsOf(t),
                    o.optLong("m", 0L), o.optLong("d", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, channelId: String, list: List<Bit>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(JSONObject().put("t", b.text).put("s", b.strength)
                .put("k", JSONArray(b.keywords)).put("m", b.lastMs).put("d", b.lastDeployMs))
        }
        prefs(ctx).edit().putString(keyOf(channelId), arr.toString()).apply()
    }

    /** Record/reinforce a channel bit. Near-duplicate (keyword overlap) reinforces instead of clutters. */
    @Synchronized
    fun remember(ctx: Context, channelId: String, text: String, nowMs: Long) {
        val t = text.trim().take(160); if (t.length < 6) return
        val kw = keywordsOf(t)
        val list = load(ctx, channelId).toMutableList()
        val hit = list.indexOfFirst { b ->
            b.text.equals(t, true) || kw.isNotEmpty() && b.keywords.count { it in kw } >= 3
        }
        if (hit >= 0) {
            val b = list[hit]
            list[hit] = b.copy(strength = (b.strength + 1).coerceAtMost(20), lastMs = nowMs)
        } else {
            list.add(Bit(t, 2, kw, nowMs, 0L))
        }
        val trimmed = if (list.size > DiscordBotLimits.CHANNEL_MEMORY_STORE_MAX)
            list.sortedWith(compareByDescending<Bit> { it.strength }.thenByDescending { it.lastMs })
                .take(DiscordBotLimits.CHANNEL_MEMORY_STORE_MAX)
        else list
        save(ctx, channelId, trimmed)
    }

    /** Reinforce any bit the incoming [text] references (recognising the running joke). */
    @Synchronized
    fun reinforceReferenced(ctx: Context, channelId: String, text: String, nowMs: Long) {
        val want = keywordsOf(text).toSet(); if (want.isEmpty()) return
        val list = load(ctx, channelId)
        var changed = false
        val out = list.map { b ->
            if (b.keywords.count { it in want } >= 2) { changed = true; b.copy(strength = (b.strength + 1).coerceAtMost(20), lastMs = nowMs) }
            else b
        }
        if (changed) save(ctx, channelId, out)
    }

    /**
     * Pick AT MOST ONE bit to lean on right now — the occasional-deployment gate. A bit is *eligible*
     * when it matches the current message text or a situational [cues] word (e.g. "image" when someone
     * posted one) AND its deploy cooldown has elapsed. Among eligible bits, a probability roll decides
     * whether to surface anything at all, so most triggers pass silently. On a hit the chosen bit's
     * deploy time is stamped so it won't recur for a while. Returns the bit text, or null.
     */
    @Synchronized
    fun pickDeployable(ctx: Context, channelId: String, text: String, cues: Set<String>, nowMs: Long): String? {
        val list = load(ctx, channelId)
        if (list.isEmpty()) return null
        val want = keywordsOf(text).toSet()
        val eligible = list.filter { b ->
            (nowMs - b.lastDeployMs) >= DiscordBotLimits.CHANNEL_BIT_DEPLOY_COOLDOWN_MS &&
                (matches(b.keywords, want) || matchesCue(b.keywords, cues))
        }
        if (eligible.isEmpty()) return null
        // Occasional, not every-time: roll first so most eligible triggers still pass with no bit.
        if (kotlin.random.Random.nextInt(100) >= DiscordBotLimits.CHANNEL_BIT_DEPLOY_CHANCE) return null
        val chosen = eligible.maxByOrNull { it.strength } ?: return null
        save(ctx, channelId, list.map { if (it.text == chosen.text) it.copy(lastDeployMs = nowMs) else it })
        return chosen.text
    }

    private fun matches(bitKw: List<String>, want: Set<String>): Boolean =
        want.isNotEmpty() && bitKw.count { it in want } >= 1

    /** A cue matches loosely (stem overlap) so "image" hits a bit about "images". */
    private fun matchesCue(bitKw: List<String>, cues: Set<String>): Boolean {
        if (cues.isEmpty()) return false
        return bitKw.any { k -> cues.any { c -> k.contains(c) || c.contains(k) } }
    }

    private val STOP = setOf(
        "the","a","an","and","or","but","to","of","in","on","for","with","is","are","was","were",
        "be","this","that","it","its","you","your","they","we","just","like","lol","cardinal","here"
    )
    private fun keywordsOf(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase())
            .map { it.value }.filter { it.length >= 3 && it !in STOP }.distinct().take(12).toList()

    // ── admin ──
    fun listAll(ctx: Context): Map<String, List<Bit>> {
        val out = LinkedHashMap<String, List<Bit>>()
        for ((k, _) in prefs(ctx).all) {
            if (!k.startsWith(KEY_PREFIX)) continue
            val ch = k.removePrefix(KEY_PREFIX)
            val bits = load(ctx, ch)
            if (bits.isNotEmpty()) out[ch] = bits.sortedByDescending { it.strength }
        }
        return out
    }
    @Synchronized
    fun delete(ctx: Context, channelId: String, text: String) {
        save(ctx, channelId, load(ctx, channelId).filterNot { it.text.equals(text, true) })
    }
    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply() }
}
