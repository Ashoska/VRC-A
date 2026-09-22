package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-user memory keyed by Discord user id — "the right facts on the right person".
 *
 * A card is tiny and durable: who they are to Cardinal, a few facts, running bits, how they like
 * to be addressed (uncapped nicknames + a single PREFERRED name Cardinal actually uses), the
 * language(s) they speak, last sentiment, and a "how to treat them" line. The STORE is unbounded
 * (text is tiny); only per-prompt INJECTION is retrieval-limited ([renderForPrompt]).
 *
 * Attribution is handled upstream (the reply tail names a person; the service resolves name→id),
 * so facts land on the correct card. Light **poisoning guards** here reject obvious prompt-injection
 * text and self-referential nicknames. Admin can view/search (by name OR nickname)/edit/pin/teach/
 * delete. Plain SharedPreferences, one JSON value per `u_<id>` key.
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
        val nicknames: List<String> = emptyList(),
        val preferredNick: String = "",
        val language: String = "",
        val alsoSpeaks: List<String> = emptyList(),
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
            nicknames = strList(o.optJSONArray("nk")),
            preferredNick = o.optString("pn"),
            language = o.optString("lang"),
            alsoSpeaks = strList(o.optJSONArray("also")),
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
            .put("f", JSONArray(card.facts))               // store UNBOUNDED — injection is capped
            .put("b", JSONArray(card.bits.takeLast(8)))
            .put("nk", JSONArray(card.nicknames))
            .put("pn", card.preferredNick)
            .put("lang", card.language)
            .put("also", JSONArray(card.alsoSpeaks))
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

    // ── poisoning guards ──
    private val POISON = Regex(
        "(?i)ignore (all|previous|the above)|system prompt|you are (now|an|a )|disregard|" +
        "new instructions|forget (everything|your)|jailbreak|pretend to be"
    )
    // Ephemeral "chatter" phrasings — momentary conversation actions, NOT durable facts about a
    // person. Dropped so a card fills with who someone IS, not a log of what they just said.
    private val EPHEMERAL = Regex(
        "(?i)^(mentioned|talked about|talking about|was talking|is talking|brought up|referenced|" +
        "imagined|posted|shared|said( that)?|says|asked( about| if)?|wanted to know|joked( about)?|" +
        "was saying|is saying|discussed|responded|replied|reacted|greeted|complained|commented)\\b"
    )
    private fun cleanFact(s: String): String? {
        val t = s.trim()
        if (t.length < 2 || t.length > 200) return null
        if (POISON.containsMatchIn(t)) return null
        if (EPHEMERAL.containsMatchIn(t)) return null
        if (t.contains("http://") || t.contains("https://")) return null
        return t
    }
    private fun cleanNick(s: String, ownNames: Set<String>): String? {
        val t = s.trim()
        if (t.length < 2 || t.length > 32) return null
        if (POISON.containsMatchIn(t)) return null
        if (ownNames.any { t.equals(it, true) }) return null
        return t
    }

    // ── near-duplicate fact handling (merge, don't pile up paraphrases) ──
    private fun norm(s: String): String =
        Regex("[^\\p{L}\\p{N} ]").replace(s.lowercase(), " ").replace(Regex("\\s+"), " ").trim()
    private fun toks(s: String): Set<String> = norm(s).split(' ').filter { it.length >= 3 }.toSet()
    /** Two facts are "the same fact" if one contains the other, or their words heavily overlap. */
    private fun similar(a: String, b: String): Boolean {
        val na = norm(a); val nb = norm(b)
        if (na.isBlank() || nb.isBlank()) return false
        if (na == nb || na.contains(nb) || nb.contains(na)) return true
        val ta = toks(a); val tb = toks(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val inter = ta.count { it in tb }
        val union = (ta + tb).size
        return union > 0 && inter.toDouble() / union >= 0.6
    }
    /** Merge [incoming] into [existing]: a near-duplicate REPLACES with the more informative (longer)
     *  version instead of adding a second; genuinely new facts are appended. */
    private fun mergeFacts(existing: List<String>, incoming: List<String>): List<String> {
        val out = existing.toMutableList()
        for (inc in incoming) {
            val i = out.indexOfFirst { similar(it, inc) }
            if (i >= 0) { if (inc.length > out[i].length) out[i] = inc }   // corrected/fuller wins
            else out.add(inc)
        }
        return out.distinct()
    }

    /**
     * Merge a model-proposed memory delta (from the reply tail). Recognised keys:
     * `facts` (array), `bit`, `nickname`/`nicknames`, `preferredName` (sets the address Cardinal
     * uses), `language`, `alsoSpeaks`, `sentiment`, `relationship`, `howToTreat`. All text runs the
     * poisoning guards. Facts are appended+deduped (unbounded); nicknames merged (self-name filtered).
     */
    fun applyDelta(ctx: Context, id: String, name: String, delta: JSONObject) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id)
        val ownNames = setOf(name, cur.name).filter { it.isNotBlank() }.toSet()

        // Forget: drop facts the model says are no longer true (fuzzy match) — but never on a
        // PINNED (admin-protected) card. Then merge in new facts, collapsing near-duplicates.
        val forget = strList(delta.optJSONArray("forget"))
        val kept = if (cur.pinned) cur.facts
            else cur.facts.filter { f -> forget.none { similar(f, it) } }
        val selfCollapsed = mergeFacts(emptyList(), kept)   // clean up existing near-dups too
        val newFacts = strList(delta.optJSONArray("facts")).mapNotNull { cleanFact(it) }
        val mergedFacts = mergeFacts(selfCollapsed, newFacts)

        val bit = cleanFact(delta.optString("bit"))
        val mergedBits = if (bit != null)
            LinkedHashSet<String>().apply { addAll(cur.bits); add(bit) }.toList().takeLast(8)
        else cur.bits

        val nick = delta.optString("nickname").trim()
        val nickArr = strList(delta.optJSONArray("nicknames"))
        val incomingNicks = (listOf(nick) + nickArr).mapNotNull { cleanNick(it, ownNames) }
        val mergedNicks = LinkedHashSet<String>().apply { addAll(cur.nicknames); addAll(incomingNicks) }.toList()

        // A PREFERRED name is the one Cardinal should actually use to address them.
        val preferred = cleanNick(delta.optString("preferredName"), ownNames)
            ?: cur.preferredNick.ifBlank { "" }

        val also = LinkedHashSet<String>().apply {
            addAll(cur.alsoSpeaks); addAll(strList(delta.optJSONArray("alsoSpeaks")))
        }.map { it.trim() }.filter { it.isNotBlank() && it.length <= 24 }

        save(ctx, cur.copy(
            name = name.ifBlank { cur.name },
            facts = mergedFacts,
            bits = mergedBits,
            nicknames = mergedNicks,
            preferredNick = preferred,
            language = delta.optString("language").trim().take(24).ifBlank { cur.language },
            alsoSpeaks = also,
            sentiment = delta.optString("sentiment").trim().take(60).ifBlank { cur.sentiment },
            relationship = delta.optString("relationship").trim().take(80).ifBlank { cur.relationship },
            howToTreat = delta.optString("howToTreat").trim().take(120).ifBlank { cur.howToTreat },
        ))
    }

    /** The name Cardinal should address this person by: their PREFERRED nick, else display name. */
    fun addressName(card: Card): String = card.preferredNick.ifBlank { card.name.ifBlank { "them" } }

    /**
     * RETRIEVAL-LIMITED prompt block for one card: pinned/relationship always, then the facts most
     * relevant to [keywords] (up to [DiscordBotLimits.USER_FACTS_INJECT]), the preferred address,
     * language, and the strongest bit. Empty when there's nothing worth saying.
     */
    fun renderForPrompt(card: Card, keywords: Set<String>): String {
        val sb = StringBuilder()
        val who = addressName(card)
        sb.append(who)
        val aka = card.nicknames.filter { !it.equals(who, true) }
        if (aka.isNotEmpty()) sb.append(" (also called ").append(aka.take(4).joinToString(", ")).append(")")
        if (card.relationship.isNotBlank()) sb.append(" — ").append(card.relationship)
        sb.append('\n')
        if (card.language.isNotBlank())
            sb.append("  speaks: ").append(card.language)
                .append(if (card.alsoSpeaks.isNotEmpty()) " (+ ${card.alsoSpeaks.joinToString(", ")})" else "").append('\n')
        if (card.sentiment.isNotBlank()) sb.append("  vibe: ").append(card.sentiment).append('\n')
        if (card.howToTreat.isNotBlank()) sb.append("  with them: ").append(card.howToTreat).append('\n')
        val facts = pickFacts(card.facts, keywords, DiscordBotLimits.USER_FACTS_INJECT)
        facts.forEach { sb.append("  · ").append(it).append('\n') }
        if (card.bits.isNotEmpty()) sb.append("  bit: ").append(card.bits.last()).append('\n')
        val out = sb.toString().trim()
        return if (out == who) "" else out.take(DiscordBotLimits.USER_CARD_MAX_CHARS)
    }

    /** Keyword-relevance pick with a recency-ish fallback so a card with no match still says something. */
    private fun pickFacts(facts: List<String>, keywords: Set<String>, limit: Int): List<String> {
        if (facts.isEmpty()) return emptyList()
        if (keywords.isEmpty()) return facts.takeLast(limit)
        val scored = facts.map { f ->
            val fl = f.lowercase()
            f to keywords.count { fl.contains(it) }
        }
        val relevant = scored.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
        return (relevant + facts.reversed()).distinct().take(limit)
    }

    /** Render the ACTIVE participants' cards for a reply prompt, folded to what's relevant now. */
    fun activeCardsBlock(ctx: Context, ids: Collection<String>, keywords: Set<String>): String {
        val blocks = ids.distinct().mapNotNull { load(ctx, it) }
            .filter { it.facts.isNotEmpty() || it.relationship.isNotBlank() || it.bits.isNotEmpty() ||
                it.howToTreat.isNotBlank() || it.preferredNick.isNotBlank() || it.language.isNotBlank() }
            .map { renderForPrompt(it, keywords) }
            .filter { it.isNotBlank() }
        return if (blocks.isEmpty()) "" else "People here you know:\n" + blocks.joinToString("\n")
    }

    // ── nickname-aware resolution ──
    /**
     * Build a name/nickname → id index across every known card, so a reply tail that refers to a
     * person by a NICKNAME still lands their facts on the right card. Names lowercased+trimmed.
     */
    fun nameIndex(ctx: Context): Map<String, String> {
        val out = HashMap<String, String>()
        list(ctx).forEach { c ->
            fun put(s: String) { val k = s.lowercase().trim(); if (k.isNotBlank() && k !in out) out[k] = c.id }
            if (c.name.isNotBlank()) put(c.name)
            if (c.preferredNick.isNotBlank()) put(c.preferredNick)
            c.nicknames.forEach { put(it) }
        }
        return out
    }

    // ── Admin surface ──────────────────────────────────────────────────────
    fun list(ctx: Context): List<Card> =
        prefs(ctx).all.keys.filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { k -> prefs(ctx).getString(k, null)?.let { parse(k.removePrefix(KEY_PREFIX), it) } }
            .sortedByDescending { it.lastSeenMs }

    /** Admin search: matches name, id, nicknames, facts, relationship, sentiment, OR language. */
    fun search(ctx: Context, query: String): List<Card> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return list(ctx)
        return list(ctx).filter { c ->
            c.name.lowercase().contains(q) || c.id.contains(q) ||
                c.preferredNick.lowercase().contains(q) ||
                c.nicknames.any { it.lowercase().contains(q) } ||
                c.facts.any { it.lowercase().contains(q) } ||
                c.relationship.lowercase().contains(q) || c.sentiment.lowercase().contains(q) ||
                c.language.lowercase().contains(q)
        }
    }

    fun count(ctx: Context): Int = prefs(ctx).all.keys.count { it.startsWith(KEY_PREFIX) }

    fun delete(ctx: Context, id: String) { prefs(ctx).edit().remove(keyOf(id)).apply() }

    /** Admin teach: pin a fact so it's protected + always injected. */
    fun teachFact(ctx: Context, id: String, fact: String, name: String = "") {
        val f = fact.trim(); if (id.isBlank() || f.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id, name = name)
        val facts = (listOf(f) + cur.facts).distinct()
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
        preferredNick: String = "", language: String = "",
    ) {
        val cur = load(ctx, id) ?: Card(id = id)
        save(ctx, cur.copy(
            relationship = relationship.trim(),
            facts = facts.map { it.trim() }.filter { it.isNotBlank() },
            sentiment = sentiment.trim(),
            howToTreat = howToTreat.trim(),
            preferredNick = preferredNick.trim().ifBlank { cur.preferredNick },
            language = language.trim().ifBlank { cur.language },
        ))
    }
}
