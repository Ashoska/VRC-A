package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-user memory keyed by Discord user id — the "shard system" done as key-by-id (simpler and
 * cheaper than sharding one blob: a reply loads ONLY the 1-3 active participants' cards, never
 * the whole store, so a 40-person server costs the same as a 3-person one).
 *
 * Each card is DELIBERATELY tiny (not raw logs — logs are the token killer): who they are to
 * Cardinal, a few durable facts, running bits with them, last sentiment, and a "how to treat
 * them" line. Updated async by the cheap model (the reply's deltas tail / a memory pass), never
 * on the hot reply path beyond a cheap merge. Admin can view/search/edit/pin/redact/delete/teach
 * (memory is moderatable for bad facts + privacy; personality is not).
 *
 * Plain SharedPreferences, one JSON value per `u_<id>` key.
 */
object UserMemoryStore {
    private const val PREFS = "vrca_discord_user_mem"
    private const val KEY_PREFIX = "u_"

    data class Card(
        val id: String,
        val name: String = "",
        val relationship: String = "",
        val facts: List<String> = emptyList(),
        val bits: List<String> = emptyList(),
        val sentiment: String = "",
        val howToTreat: String = "",
        val lastSeenMs: Long = 0L,
        val interactions: Int = 0,
        val pinned: Boolean = false,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyOf(id: String) = KEY_PREFIX + id

    fun load(ctx: Context, id: String): Card? {
        val raw = prefs(ctx).getString(keyOf(id), null) ?: return null
        return parse(id, raw)
    }

    private fun parse(id: String, raw: String): Card? = try {
        val o = JSONObject(raw)
        Card(
            id = id,
            name = o.optString("n"),
            relationship = o.optString("rel"),
            facts = strList(o.optJSONArray("f")),
            bits = strList(o.optJSONArray("b")),
            sentiment = o.optString("s"),
            howToTreat = o.optString("h"),
            lastSeenMs = o.optLong("ls", 0L),
            interactions = o.optInt("ic", 0),
            pinned = o.optBoolean("p", false),
        )
    } catch (_: Exception) { null }

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } }

    fun save(ctx: Context, card: Card) {
        val o = JSONObject()
            .put("n", card.name)
            .put("rel", card.relationship)
            .put("f", JSONArray(card.facts.take(DiscordBotLimits.USER_CARD_MAX_FACTS)))
            .put("b", JSONArray(card.bits.take(DiscordBotLimits.USER_CARD_MAX_BITS)))
            .put("s", card.sentiment)
            .put("h", card.howToTreat)
            .put("ls", card.lastSeenMs)
            .put("ic", card.interactions)
            .put("p", card.pinned)
        prefs(ctx).edit().putString(keyOf(card.id), o.toString()).apply()
    }

    /** Mark a user seen (creates a stub card so a first-timer is trackable). Cheap, sync. */
    fun touch(ctx: Context, id: String, name: String) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id)
        save(ctx, cur.copy(
            name = name.ifBlank { cur.name },
            lastSeenMs = System.currentTimeMillis(),
            interactions = cur.interactions + 1,
        ))
    }

    /**
     * Merge a model-proposed memory delta (from the reply's deltas tail). Recognised keys:
     * `facts` (array, appended + deduped), `bit` (string), `sentiment`, `relationship`,
     * `howToTreat`. Pinned/admin-set facts are never dropped (they sort to the front and the
     * cap trims the newest model facts first).
     */
    fun applyDelta(ctx: Context, id: String, name: String, delta: JSONObject) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id)
        val newFacts = strList(delta.optJSONArray("facts"))
        val mergedFacts = LinkedHashSet<String>().apply {
            addAll(cur.facts); addAll(newFacts)
        }.toList().take(DiscordBotLimits.USER_CARD_MAX_FACTS)
        val bit = delta.optString("bit").trim()
        val mergedBits = if (bit.isNotBlank())
            LinkedHashSet<String>().apply { addAll(cur.bits); add(bit) }.toList()
                .takeLast(DiscordBotLimits.USER_CARD_MAX_BITS)
        else cur.bits
        save(ctx, cur.copy(
            name = name.ifBlank { cur.name },
            facts = mergedFacts,
            bits = mergedBits,
            sentiment = delta.optString("sentiment").trim().ifBlank { cur.sentiment },
            relationship = delta.optString("relationship").trim().ifBlank { cur.relationship },
            howToTreat = delta.optString("howToTreat").trim().ifBlank { cur.howToTreat },
        ))
    }

    /** Compact prompt block for one card (capped). Empty when there's nothing worth saying. */
    fun render(card: Card): String {
        val sb = StringBuilder()
        val who = card.name.ifBlank { "them" }
        sb.append(who)
        if (card.relationship.isNotBlank()) sb.append(" — ").append(card.relationship)
        sb.append('\n')
        if (card.sentiment.isNotBlank()) sb.append("  vibe: ").append(card.sentiment).append('\n')
        if (card.howToTreat.isNotBlank()) sb.append("  with them: ").append(card.howToTreat).append('\n')
        if (card.facts.isNotEmpty()) card.facts.forEach { sb.append("  · ").append(it).append('\n') }
        if (card.bits.isNotEmpty()) sb.append("  bits: ").append(card.bits.joinToString("; ")).append('\n')
        val out = sb.toString().trim()
        return if (out == who) "" else out.take(DiscordBotLimits.USER_CARD_MAX_CHARS)
    }

    /** Render only the ACTIVE participants' cards for a reply prompt (the token-efficiency lever). */
    fun activeCardsBlock(ctx: Context, ids: Collection<String>): String {
        val blocks = ids.distinct().mapNotNull { load(ctx, it) }
            .filter { it.facts.isNotEmpty() || it.relationship.isNotBlank() || it.bits.isNotEmpty() || it.howToTreat.isNotBlank() }
            .map { render(it) }
            .filter { it.isNotBlank() }
        return if (blocks.isEmpty()) "" else "People here you know:\n" + blocks.joinToString("\n")
    }

    // ── Admin surface ──────────────────────────────────────────────────────
    fun list(ctx: Context): List<Card> =
        prefs(ctx).all.keys.filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { k -> prefs(ctx).getString(k, null)?.let { parse(k.removePrefix(KEY_PREFIX), it) } }
            .sortedByDescending { it.lastSeenMs }

    fun search(ctx: Context, query: String): List<Card> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return list(ctx)
        return list(ctx).filter { c ->
            c.name.lowercase().contains(q) || c.id.contains(q) ||
                c.facts.any { it.lowercase().contains(q) } ||
                c.relationship.lowercase().contains(q) || c.sentiment.lowercase().contains(q)
        }
    }

    fun count(ctx: Context): Int = prefs(ctx).all.keys.count { it.startsWith(KEY_PREFIX) }

    fun delete(ctx: Context, id: String) { prefs(ctx).edit().remove(keyOf(id)).apply() }

    /** Admin teach: pin a fact so reflection/decay never drops it. */
    fun teachFact(ctx: Context, id: String, fact: String, name: String = "") {
        val f = fact.trim(); if (id.isBlank() || f.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id, name = name)
        val facts = (listOf(f) + cur.facts).distinct().take(DiscordBotLimits.USER_CARD_MAX_FACTS)
        save(ctx, cur.copy(facts = facts, pinned = true, name = name.ifBlank { cur.name }))
    }

    fun setPinned(ctx: Context, id: String, pinned: Boolean) {
        val cur = load(ctx, id) ?: return
        save(ctx, cur.copy(pinned = pinned))
    }

    /** Admin edit: overwrite the human-editable fields verbatim. */
    fun edit(
        ctx: Context, id: String,
        relationship: String, facts: List<String>, sentiment: String, howToTreat: String,
    ) {
        val cur = load(ctx, id) ?: Card(id = id)
        save(ctx, cur.copy(
            relationship = relationship.trim(),
            facts = facts.map { it.trim() }.filter { it.isNotBlank() }.take(DiscordBotLimits.USER_CARD_MAX_FACTS),
            sentiment = sentiment.trim(),
            howToTreat = howToTreat.trim(),
        ))
    }
}
