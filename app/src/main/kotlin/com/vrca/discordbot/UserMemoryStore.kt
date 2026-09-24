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
        val talkStyle: String = "",
        val lastSeenMs: Long = 0L,
        val interactions: Int = 0,
        val pinned: Boolean = false,
        val avoid: List<String> = emptyList(),   // things they asked Cardinal to stop doing to them
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
            talkStyle = o.optString("ts"),
            lastSeenMs = o.optLong("ls", 0L),
            interactions = o.optInt("ic", 0),
            pinned = o.optBoolean("p", false),
            avoid = strList(o.optJSONArray("av")),
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
            .put("ts", card.talkStyle)
            .put("ls", card.lastSeenMs)
            .put("ic", card.interactions)
            .put("p", card.pinned)
            .put("av", JSONArray(card.avoid.takeLast(4)))
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
        "was saying|is saying|discussed|responded|replied|reacted|greeted|complained|commented|" +
        "pinged|tagged|@ed|dm'?ed|messaged)\\b"
    )
    // META text about USING Cardinal / the app itself — never a fact about who a person IS. Rejects
    // the "I asked it to edit my profile", "changed nickname recently", "reset the bot" junk that
    // filled cards. Bot-meta / self-referential app plumbing is not durable identity.
    private val META = Regex(
        "(?i)\\b(profile|personality|memory|memories|nickname changed|changed (their |his |her )?nickname|" +
        "edit(ed|ing)? (my |their |the )?(profile|memory|card|name)|reset|the bot|cardinal'?s|" +
        "asked (it|cardinal|the bot)|wants? (you|cardinal) to|told (you|cardinal|it) to|" +
        "no message|with no message|pinged|prompt|instructions?)\\b"
    )
    // Plans and states tied to right now ("going to bed tonight", "is sick today") stop being true in
    // a day or two — they're not who someone is, and a stored fact never expires.
    private val TRANSIENT = Regex(
        "(?i)\\b(today|tonight|tomorrow|yesterday|this (morning|afternoon|evening|week|weekend)|right now|" +
        "at the moment|later today|next few (hours|days))\\b"
    )
    // Filler "facts" that describe a chat mood, not the person ("has a sense of humor about it").
    private val GENERIC_FACT = Regex(
        "(?i)^(has an? (good |great |dark |dry |weird )?sense of humou?r|seems (to|like)|is (funny|nice|cool|friendly|chill|hilarious|sarcastic)\\b)"
    )
    /** "alice plays X" → "plays X", "alice's cat …" → "their cat …" (the card already says who). */
    private fun stripOwnName(fact: String, names: Collection<String>): String {
        val t = fact.trim()
        val low = t.lowercase()
        for (n in names.map { it.lowercase().trim() }.filter { it.length >= 2 }.sortedByDescending { it.length }) {
            if (low.startsWith("$n's ") || low.startsWith("$n’s ")) return "their " + t.substring(n.length + 3)
            if (low.startsWith("$n ")) return t.substring(n.length + 1).trim()
        }
        return t
    }
    // The cheap model sometimes fills a field it knows nothing about with "none" / "n/a" / "unknown".
    private val NONE_VALUE = Regex("(?i)^(none|n/?a|null|nil|unknown|not (specified|mentioned|sure|clear|known)|nothing|no|-+|\\?+|same|unchanged)\\.?$")
    private fun value(s: String?): String = s?.trim()?.takeUnless { NONE_VALUE.matches(it) }.orEmpty()
    // A relationship that says nothing ("member") would otherwise be kept and block a real one later.
    private val GENERIC_REL = Regex("(?i)^(a |an |the )?(regular |server |discord |normal )?(member|user|participant|person|chatter|someone|human|guy|people)s?\\.?$")

    // Guesses aren't facts ("possibly a friend of Cardinal's", "is a member of the server").
    private val SPECULATION = Regex("(?i)\\b(possibly|probably|maybe|perhaps|might be|seems to|likely|apparently)\\b|" +
        "\\b(a )?member of (the|this) (server|discord|chat)\\b|" +
        // How they play along with Cardinal is chat, not who they are (their relationship to him has its own slot).
        "\\bcardinal\\b")
    private fun cleanFact(s: String): String? {
        val t = s.trim()
        if (NONE_VALUE.matches(t)) return null
        if (t.length < 2 || t.length > 200) return null
        if (POISON.containsMatchIn(t)) return null
        if (EPHEMERAL.containsMatchIn(t)) return null
        if (META.containsMatchIn(t)) return null
        if (TRANSIENT.containsMatchIn(t)) return null
        if (GENERIC_FACT.containsMatchIn(t)) return null
        if (SPECULATION.containsMatchIn(t)) return null
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
    private val FACT_FILLER = setOf("has", "have", "the", "and", "with", "named", "called", "their", "they", "them",
        "are", "was", "were", "who", "that", "this", "for", "from", "its", "into", "about", "owns", "own", "pet")
    private fun toks(s: String): Set<String> = norm(s).split(' ').filter { it.length >= 3 && it !in FACT_FILLER }.toSet()
    /** Two facts are "the same fact" if one contains the other AS WHOLE WORDS, or their words heavily
     *  overlap. (Raw substring containment made "ali" match "Australia" and "al" match "Valorant".) */
    private fun similar(a: String, b: String): Boolean {
        val na = norm(a); val nb = norm(b)
        if (na.isBlank() || nb.isBlank()) return false
        if (na == nb || " $na ".contains(" $nb ") || " $nb ".contains(" $na ")) return true
        val ta = toks(a); val tb = toks(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val inter = ta.count { it in tb }
        val union = (ta + tb).size
        return union > 0 && inter.toDouble() / union >= 0.5
    }
    // Words that carry no information of their own when a fact just restates a name/relationship
    // ("goes by ali", "is the server creator", "a friend").
    private val IDENTITY_FILLER = setOf(
        "is", "a", "an", "the", "they", "their", "he", "she", "his", "her", "them", "are", "was",
        "goes", "go", "by", "called", "call", "name", "named", "nickname", "known", "as", "aka",
        "also", "server", "here", "of", "our", "this", "my", "to", "be",
    )
    private fun contentWords(s: String): List<String> = norm(s).split(' ').filter { it.isNotBlank() && it !in IDENTITY_FILLER }

    /** True when [fact] says nothing beyond [value] (same words once filler is removed). */
    private fun restatesIdentity(fact: String, value: String): Boolean {
        val f = contentWords(fact); val v = contentWords(value)
        if (f.isEmpty() || v.isEmpty()) return norm(fact) == norm(value)
        return f.toSet() == v.toSet()
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
     * [correcting] = the learner was shown this card's stored facts because the chat had a correction
     * or complaint in it: only then are `forget` / `notNickname` / `avoid` honoured and may the
     * relationship be replaced (outside that mode it's filled once and kept).
     */
    fun applyDelta(ctx: Context, id: String, name: String, delta: JSONObject, correcting: Boolean = false) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id)
        val ownNames = setOf(name, cur.name).filter { it.isNotBlank() }.toSet()

        // Forget: drop facts the model says are no longer true (fuzzy match) — but never on a
        // PINNED (admin-protected) card. Then merge in new facts, collapsing near-duplicates.
        val forget = if (correcting) strList(delta.optJSONArray("forget")) else emptyList()
        val kept = if (cur.pinned) cur.facts
            else cur.facts.filter { f -> forget.none { similar(f, it) } }
        val selfCollapsed = mergeFacts(emptyList(), kept)   // clean up existing near-dups too
        val allNames = ownNames + cur.nicknames + listOf(cur.preferredNick)
        val newFacts = strList(delta.optJSONArray("facts")).map { stripOwnName(it, allNames) }.mapNotNull { cleanFact(it) }
        val mergedFacts = mergeFacts(selfCollapsed, newFacts)

        val bit = cleanFact(delta.optString("bit"))
        val mergedBits = if (bit != null)
            LinkedHashSet<String>().apply { addAll(cur.bits); add(bit) }.toList().takeLast(8)
        else cur.bits

        val nick = value(delta.optString("nickname"))
        val nickArr = strList(delta.optJSONArray("nicknames"))
        val incomingNicks = (listOf(nick) + nickArr).mapNotNull { cleanNick(it, ownNames) }
        // "Don't call me X": drop that name everywhere on the card and never re-learn it this pass.
        val notNick = if (correcting) value(delta.optString("notNickname")).lowercase() else ""
        val mergedNicks = LinkedHashSet<String>().apply { addAll(cur.nicknames); addAll(incomingNicks) }.toList()
            .filterNot { notNick.isNotBlank() && it.equals(notNick, true) }

        // A PREFERRED name is the one Cardinal should actually use to address them.
        val preferred = (cleanNick(value(delta.optString("preferredName")), ownNames)
            ?: cur.preferredNick.ifBlank { "" }).takeUnless { notNick.isNotBlank() && it.equals(notNick, true) }.orEmpty()
        val avoidNew = if (correcting) cleanFact(value(delta.optString("avoid")))?.take(100) else null
        val avoid = (cur.avoid + listOfNotNull(avoidNew) +
            listOfNotNull(notNick.takeIf { it.isNotBlank() }?.let { "calling them \"$it\"" }))
            .fold(ArrayList<String>()) { acc, a -> if (acc.none { similar(it, a) || (notNick.isNotBlank() && norm(it).contains(notNick) && norm(a).contains(notNick)) }) acc.add(a); acc }
            .takeLast(4)

        // One line in another language doesn't change what someone mainly speaks: a new language on a
        // card that already has one is recorded as "also speaks" instead of replacing it.
        // English is the default (never shown), so the learner saying "English" adds nothing.
        val incomingLang = value(delta.optString("language")).take(24)
            .takeUnless { it.contains("english", true) || it.equals("en", true) || it.contains("assum", true) ||
                it.contains("unknown", true) || it.contains("?") }.orEmpty()
        val keepMainLang = cur.language.isNotBlank() && incomingLang.isNotBlank() && !incomingLang.equals(cur.language, true)
        val also = LinkedHashSet<String>().apply {
            addAll(cur.alsoSpeaks); addAll(strList(delta.optJSONArray("alsoSpeaks")).map { value(it) })
            if (keepMainLang) add(incomingLang)
        }.map { it.trim() }.filter { it.isNotBlank() && it.length <= 24 && !it.equals(cur.language, true) &&
            !it.equals("english", true) }
            .distinctBy { it.lowercase() }

        // Relationship / how-to-treat are filled once and then kept: the cheap learner re-describing
        // the chat's mood ("teasing and playful with Cardinal") used to overwrite a real relationship
        // ("server regular, basically runs events"). The admin can still edit them.
        val incomingRel = value(delta.optString("relationship")).take(80).takeUnless { GENERIC_REL.matches(it) }.orEmpty()
        val relationship = if (correcting && incomingRel.isNotBlank() && !cur.pinned) incomingRel
            else cur.relationship.ifBlank { incomingRel }
        // Drop facts that ONLY restate an identity field (name/nick/relationship/sentiment) — those live
        // in their own slots, so a fact like "Creator" when relationship=Creator is noise. A fact that
        // merely CONTAINS one of those words ("best friends with carol", "regular at the climbing gym",
        // "lives in Australia" for nickname "ali") is real information and is kept.
        val identity = (listOf(relationship, cur.name, name, preferred, cur.sentiment,
            value(delta.optString("sentiment"))) + mergedNicks)
            .map { it.trim() }.filter { it.isNotBlank() }
        val cleanedFacts = mergedFacts.filterNot { f -> identity.any { restatesIdentity(f, it) } }

        save(ctx, cur.copy(
            name = cur.name.ifBlank { name.take(60) },   // NEVER let a nickname overwrite the real name
            facts = cleanedFacts,
            bits = mergedBits,
            nicknames = mergedNicks,
            preferredNick = preferred,
            language = if (keepMainLang) cur.language else incomingLang.ifBlank { cur.language },
            alsoSpeaks = also,
            sentiment = value(delta.optString("sentiment")).take(60).ifBlank { cur.sentiment },
            relationship = relationship,
            howToTreat = cur.howToTreat.ifBlank { value(delta.optString("howToTreat")).take(120) },
            talkStyle = value(delta.optString("talkStyle")).take(80).ifBlank { cur.talkStyle },
            avoid = avoid,
        ))
    }

    /**
     * What's stored about these people, as (id, name, item) rows for the learner's numbered correction
     * view: each fact, plus "goes by X" for each nickname. Only built when the chat contains a correction.
     */
    fun correctionItems(ctx: Context, ids: Collection<String>, maxPeople: Int = 6): List<Triple<String, String, String>> =
        ids.distinct().mapNotNull { load(ctx, it) }.filter { !it.pinned }
            .filter { it.facts.isNotEmpty() || it.nicknames.isNotEmpty() || it.preferredNick.isNotBlank() }
            .take(maxPeople)
            .flatMap { c ->
                val nicks = (listOf(c.preferredNick) + c.nicknames).filter { it.isNotBlank() && !it.equals(c.name, true) }
                    .distinctBy { it.lowercase() }
                c.facts.takeLast(8).map { Triple(c.id, c.name, it) } + nicks.map { Triple(c.id, c.name, "goes by $it") }
            }

    // ── Paraphrase piles ("studies AI", "can create custom AI engines", "can train AI models"…) ──
    // Word-overlap dedup can't see these (they share one short topic word), so a card whose single topic
    // collects more than [DiscordBotLimits.FACTS_PER_TOPIC] facts is sent for a cheap merge pass.
    private val TOPIC_GENERIC = setOf(
        "studies", "study", "create", "creates", "creating", "make", "makes", "making", "test", "tests", "testing",
        "train", "trains", "training", "tweak", "tweaks", "use", "uses", "using", "work", "works", "working",
        "custom", "project", "projects", "likes", "loves", "enjoys", "plays", "playing", "really", "about",
        "their", "they", "them", "have", "has", "with", "from", "into", "that", "this", "very", "good", "great",
        "able", "can", "could", "own", "owns", "named", "called", "also", "always", "often", "lot", "lots",
        // The whole server is about these, so sharing them doesn't make two facts the same subject.
        "vrchat", "discord", "server", "friends", "friend", "games", "gaming",
    )
    /** A fact's topic words: longer non-generic words, plus short ALL-CAPS ones (AI, VR, PC). */
    private fun topicKeys(fact: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(fact).map { it.value }.filter { w ->
            (w.length >= 5 && w.lowercase() !in TOPIC_GENERIC) || (w.length in 2..3 && w.all { it.isUpperCase() || it.isDigit() } && w.any { it.isLetter() })
        }.map { discordStem(it.lowercase()) }.toSet()

    /** The topic word shared by too many of this card's facts (a paraphrase pile) and those facts, or null. */
    fun crowdedTopic(card: Card): Pair<String, List<String>>? {
        val counts = HashMap<String, Int>()
        card.facts.forEach { f -> topicKeys(f).forEach { counts.merge(it, 1, Int::plus) } }
        val key = counts.filter { it.value > DiscordBotLimits.FACTS_PER_TOPIC }.maxByOrNull { it.value }?.key ?: return null
        return key to card.facts.filter { key in topicKeys(it) }
    }

    /**
     * Swap a paraphrase pile for its merged version — only if the merge is shorter and built from words that
     * were already in the pile (nothing invented). Every other fact is left exactly as it was. Pinned cards
     * are never touched.
     */
    fun replaceFacts(ctx: Context, id: String, pile: List<String>, merged: List<String>): Boolean {
        val cur = load(ctx, id) ?: return false
        if (cur.pinned || merged.isEmpty() || merged.size >= pile.size) return false
        val have = pile.flatMap { toks(it) }.toSet()
        val clean = merged.mapNotNull { cleanFact(stripOwnName(it, listOf(cur.name))) }
            .filter { f -> toks(f).let { w -> w.isNotEmpty() && w.count { it in have } * 3 >= w.size * 2 } }
            .distinct()
        if (clean.isEmpty() || clean.size >= pile.size) return false
        val pileSet = pile.toSet()
        val kept = cur.facts.filter { it !in pileSet }
        save(ctx, cur.copy(facts = kept + clean))
        return true
    }

    /** "alice: from Toronto; owns a cat named Miso" per person — so the learner only adds what's new. */
    fun knownFactsLine(ctx: Context, ids: Collection<String>, perPerson: Int = 6): String =
        ids.distinct().mapNotNull { load(ctx, it) }.filter { it.facts.isNotEmpty() && it.name.isNotBlank() }
            .joinToString("\n") { c -> c.name + ": " + c.facts.takeLast(perPerson).joinToString("; ") { it.trim().trimEnd('.') } }

    /** The chat said this stored item is wrong / unwanted: drop the fact, or stop using the nickname. */
    fun forgetItem(ctx: Context, id: String, item: String) {
        val cur = load(ctx, id) ?: return
        if (cur.pinned) return
        if (item.startsWith("goes by ")) {
            val nick = item.removePrefix("goes by ").trim()
            save(ctx, cur.copy(
                nicknames = cur.nicknames.filterNot { it.equals(nick, true) },
                preferredNick = cur.preferredNick.takeUnless { it.equals(nick, true) }.orEmpty(),
            ))
        } else save(ctx, cur.copy(facts = cur.facts.filterNot { it == item || similar(it, item) }))
    }

    /** The name Cardinal should address this person by: their PREFERRED nick, else display name. */
    fun addressName(card: Card): String = card.preferredNick.ifBlank { card.name.ifBlank { "them" } }

    /**
     * Prompt block for one card. The REAL name is the head; a casual nickname is labelled separately
     * so the model uses ONE name at a time by register (never "twinium's Michael"), and one person's
     * nicknames can never bleed onto another. [full] = a recall query directly named this person, so
     * inject their WHOLE card (all facts) rather than the keyword-relevant few. Empty when there's
     * nothing worth saying.
     */
    fun renderForPrompt(card: Card, keywords: Set<String>, full: Boolean = false): String {
        val sb = StringBuilder()
        val real = card.name.ifBlank { card.preferredNick.ifBlank { "them" } }
        sb.append(real)
        val casual = card.preferredNick.takeIf { it.isNotBlank() && !it.equals(real, true) }
        if (casual != null) sb.append("  [casual nickname: ").append(casual).append("]")
        val otherNicks = card.nicknames.filter { !it.equals(real, true) && !it.equals(casual ?: "", true) }
        if (otherNicks.isNotEmpty()) sb.append(" (also goes by: ").append(otherNicks.take(3).joinToString(", ")).append(")")
        if (card.relationship.isNotBlank()) sb.append(" — ").append(card.relationship)
        sb.append('\n')
        if (card.language.isNotBlank())
            sb.append("  speaks: ").append(card.language)
                .append(if (card.alsoSpeaks.isNotEmpty()) " (+ ${card.alsoSpeaks.joinToString(", ")})" else "").append('\n')
        if (card.sentiment.isNotBlank()) sb.append("  vibe: ").append(card.sentiment).append('\n')
        if (card.howToTreat.isNotBlank()) sb.append("  with them: ").append(card.howToTreat).append('\n')
        if (card.avoid.isNotEmpty()) sb.append("  asked you to stop: ").append(card.avoid.joinToString("; ")).append('\n')
        if (card.talkStyle.isNotBlank()) sb.append("  talk to them: ").append(card.talkStyle).append('\n')
        val factLimit = if (full) 12 else DiscordBotLimits.USER_FACTS_INJECT
        val facts = if (full) card.facts.takeLast(factLimit) else pickFacts(card.facts, keywords, factLimit)
        facts.forEach { sb.append("  · ").append(it).append('\n') }
        if (card.bits.isNotEmpty()) sb.append("  bit: ").append(card.bits.last()).append('\n')
        val out = sb.toString().trim()
        return if (out == real) "" else out.take(DiscordBotLimits.USER_CARD_MAX_CHARS)
    }

    // ── Lean prompt rendering (the app decides what's relevant) ──────────────

    /** A rendered person line + whether it carries a nickname/alias (→ the one-name rule applies). */
    data class PromptLine(val text: String, val hasNick: Boolean)

    private fun stemmedWords(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { discordStem(it.value) }.filter { it.length >= 3 }.toSet()

    /** Facts sharing a (stemmed) word with [keywords], best first. */
    private fun relevantFacts(facts: List<String>, keywords: Set<String>): List<String> {
        if (facts.isEmpty() || keywords.isEmpty()) return emptyList()
        val want = keywords.map { discordStem(it) }.toSet()
        return facts.map { f -> f to stemmedWords(f).count { it in want } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    /** "real name (goes by nick; also: a, b)" — the name header shared by both renderers. */
    private fun nameHeader(card: Card, fallbackName: String): Pair<String, Boolean> {
        val real = card.name.ifBlank { fallbackName.ifBlank { card.preferredNick.ifBlank { "them" } } }
        val casual = card.preferredNick.takeIf { it.isNotBlank() && !it.equals(real, true) }
        val aliases = card.nicknames.filter { !it.equals(real, true) && !it.equals(casual ?: "", true) }.take(2)
        val sb = StringBuilder(real)
        if (casual != null || aliases.isNotEmpty()) {
            sb.append(" (")
            if (casual != null) sb.append("goes by ").append(casual)
            if (aliases.isNotEmpty()) { if (casual != null) sb.append("; "); sb.append("also: ").append(aliases.joinToString(", ")) }
            sb.append(")")
        }
        return sb.toString() to (casual != null || aliases.isNotEmpty())
    }

    private fun cleanSentiment(s: String): String = s.replace(Regex("\\s*\\(reacted[^)]*\\)"), "").trim()

    /**
     * The person Cardinal is answering — ALWAYS included so it knows who it's talking to: name /
     * nickname, relationship, how to treat them, their language (non-English only), and what it
     * knows that's relevant now. When nothing matches the conversation it still gets their top
     * couple of facts (a regular knows a few things about the people they talk to). [recall] = they
     * asked what Cardinal knows about them → their whole card.
     */
    fun answeringLine(ctx: Context, id: String, fallbackName: String, keywords: Set<String>, recall: Boolean): PromptLine {
        val card = load(ctx, id) ?: return PromptLine(fallbackName, false)
        val (header, hasNick) = nameHeader(card, fallbackName)
        val sb = StringBuilder(header)
        if (card.relationship.isNotBlank()) sb.append(" — ").append(card.relationship.trim().trimEnd('.'))
        sb.append('.')
        if (card.howToTreat.isNotBlank()) sb.append(" With them: ").append(card.howToTreat.trim().trimEnd('.')).append('.')
        if (card.avoid.isNotEmpty()) sb.append(" Quietly never do this with them again (don't mention it): ").append(card.avoid.joinToString("; ") { it.trim().trimEnd('.') }).append('.')
        if (card.talkStyle.isNotBlank()) sb.append(" How they talk: ").append(card.talkStyle.trim().trimEnd('.')).append('.')
        val vibe = cleanSentiment(card.sentiment)
        if (vibe.isNotBlank()) sb.append(" Vibe with you: ").append(vibe).append('.')
        val lang = card.language.trim()
        if (lang.isNotBlank() && !lang.equals("english", true) && !lang.equals("en", true))
            sb.append(" Speaks ").append(lang).append('.')
        val facts = if (recall) card.facts.takeLast(12)
            else (relevantFacts(card.facts, keywords).take(DiscordBotLimits.USER_FACTS_INJECT))
                .ifEmpty { card.facts.take(DiscordBotLimits.ANSWERING_FALLBACK_FACTS) }
        if (facts.isNotEmpty()) sb.append(" About them: ").append(facts.joinToString("; ") { it.trim().trimEnd('.') }).append('.')
        if (card.bits.isNotEmpty() && (recall || relevantFacts(card.bits, keywords).isNotEmpty()))
            sb.append(" Running bit with them: ").append(card.bits.last()).append('.')
        return PromptLine(sb.toString(), hasNick)
    }

    /**
     * Someone else in the conversation (or named in it). A person the message NAMES always comes with
     * what Cardinal knows (relevant facts first, then their others, up to [namedLimit]) — they're the
     * topic. Someone merely talking nearby is included only when there's something worth knowing
     * right now: a nickname (so "ali" resolves to the right person) or facts relevant to the
     * conversation. [full] = they were asked about → their whole card. Null = leave them out.
     */
    fun otherLine(
        ctx: Context, id: String, fallbackName: String, keywords: Set<String>, full: Boolean, namedLimit: Int = 0,
    ): PromptLine? {
        val card = load(ctx, id) ?: return null
        val (header, hasNick) = nameHeader(card, fallbackName)
        val relevant = relevantFacts(card.facts, keywords)
        val facts = when {
            full -> card.facts.takeLast(12)
            namedLimit > 0 -> (relevant + card.facts).distinct().take(namedLimit)
            else -> relevant.take(2)
        }
        val rel = card.relationship.trim().trimEnd('.')
        if (!full && !hasNick && facts.isEmpty() && (namedLimit == 0 || rel.isBlank())) return null
        val sb = StringBuilder("- ").append(header)
        if (rel.isNotBlank() && (full || namedLimit > 0 || facts.isNotEmpty() || hasNick)) sb.append(" — ").append(rel)
        if (facts.isNotEmpty()) sb.append(": ").append(facts.joinToString("; ") { it.trim().trimEnd('.') })
        return PromptLine(sb.toString(), hasNick)
    }

    /**
     * "Who …?" questions with nobody named ("who runs the events here?"): the cards whose facts or
     * relationship best match the question's words, best first. Free, local, bounded.
     */
    fun searchCards(ctx: Context, keywords: Set<String>, exclude: Set<String>, max: Int): List<String> {
        val want = keywords.map { discordStem(it) }.toSet(); if (want.isEmpty()) return emptyList()
        return list(ctx).asSequence()
            .filter { it.id !in exclude }
            .map { c ->
                val words = stemmedWords(c.facts.joinToString(" ") + " " + c.relationship + " " + c.bits.joinToString(" "))
                c.id to words.count { it in want }
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(max).map { it.first }.toList()
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

    /**
     * Render the participants' cards for a reply prompt, folded to what's relevant now. [emphasizeIds]
     * are people the current message directly ASKED ABOUT (e.g. "who is Michael", "info about X") —
     * their card is rendered FULL (all facts) so Cardinal can actually answer from memory instead of
     * claiming it doesn't know someone it does. Each card is a DISTINCT person; the block header says so.
     */
    fun activeCardsBlock(
        ctx: Context, ids: Collection<String>, keywords: Set<String>, emphasizeIds: Set<String> = emptySet(),
    ): String {
        fun hasContent(c: Card) = c.facts.isNotEmpty() || c.relationship.isNotBlank() || c.bits.isNotEmpty() ||
            c.howToTreat.isNotBlank() || c.preferredNick.isNotBlank() || c.language.isNotBlank() || c.sentiment.isNotBlank()
        val blocks = ids.distinct().mapNotNull { load(ctx, it) }
            .filter { hasContent(it) }
            .map { renderForPrompt(it, keywords, full = it.id in emphasizeIds) }
            .filter { it.isNotBlank() }
        return if (blocks.isEmpty()) "" else
            "People here you know (each is a DISTINCT person — never mix up their names/nicknames/facts):\n" +
                blocks.joinToString("\n")
    }

    // ── nickname-aware resolution ──
    /**
     * Build a name/nickname → id index across every known card, so a reply tail that refers to a
     * person by a NICKNAME still lands their facts on the right card. Names lowercased+trimmed.
     */
    /** One name a person goes by: [isRealName] = their display name (vs a nickname/preferred name). */
    data class NameKey(val key: String, val id: String, val isRealName: Boolean)

    /** Every known name + nickname, lowercased — for spotting who a message is talking about. */
    fun nameEntries(ctx: Context): List<NameKey> {
        val seen = HashSet<String>()
        val out = ArrayList<NameKey>()
        list(ctx).forEach { c ->
            fun put(s: String, real: Boolean) {
                val k = s.lowercase().trim()
                if (k.isNotBlank() && seen.add(k)) out.add(NameKey(k, c.id, real))
            }
            if (c.name.isNotBlank()) put(c.name, true)
            if (c.preferredNick.isNotBlank()) put(c.preferredNick, false)
            c.nicknames.forEach { put(it, false) }
        }
        return out
    }

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
