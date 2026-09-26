package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-user memory keyed by Discord user id.
 *
 * A card holds TYPED slots instead of free-text facts: where they're from / live, their timezone, work,
 * games, hobbies, likes, dislikes, pets, and a strict "about" slot (only things they said about
 * themselves). Every note the learner proposes names a slot and the chat line that shows it; the
 * service checks that line before [addNote] stores it, so a card only ever holds what fits a slot and
 * was actually said. Single slots (from, lives) are replaced; list slots keep the newest few.
 *
 * Also: role here (relationship), nicknames + the preferred name Cardinal uses, languages seen, pronouns
 * (set only by the person), how-to-treat / avoid lines, and when they last wrote anything in chat.
 * Plain SharedPreferences, one JSON value per `u_<id>` key.
 */
object UserMemoryStore {
    private const val PREFS = "vrca_discord_user_mem"
    private const val KEY_PREFIX = "u_"

    data class Card(
        val id: String,
        val name: String = "",
        val relationship: String = "",
        /** slot → values (oldest first). See [SLOTS]. */
        val notes: Map<String, List<String>> = emptyMap(),
        val bits: List<String> = emptyList(),
        val nicknames: List<String> = emptyList(),
        val preferredNick: String = "",
        val language: String = "",
        val alsoSpeaks: List<String> = emptyList(),
        val sentiment: String = "",
        val howToTreat: String = "",
        val lastSeenMs: Long = 0L,        // their last message anywhere in chat
        val interactions: Int = 0,        // times Cardinal answered them
        val pinned: Boolean = false,
        val avoid: List<String> = emptyList(),
        // Only ever set from the person's OWN words ("my pronouns are she/they"); nobody else can change them.
        val pronouns: String = "",
        /** IANA zone id ("America/Toronto") or a fixed "UTC+2"; shown as a live UTC offset. */
        val tz: String = "",
        /** Tentative notes: "slot|value" → when first seen. Not in here = confirmed. */
        val unsure: Map<String, Long> = emptyMap(),
    )

    // ── Slots ──────────────────────────────────────────────────────────────
    /** Display order. */
    val SLOTS = listOf("work", "from", "lives", "game", "hobby", "likes", "dislikes", "pet", "about")
    private val SINGLE = setOf("from", "lives")
    private val CAP = mapOf("work" to 3, "game" to 5, "hobby" to 4, "likes" to 5, "dislikes" to 4, "pet" to 3, "about" to 5)
    val LABEL = mapOf("work" to "work", "from" to "from", "lives" to "lives in", "game" to "plays", "hobby" to "into",
        "likes" to "likes", "dislikes" to "dislikes", "pet" to "pets", "about" to "about")
    /** Words that make a slot relevant to a message even when no value word matches ("what does bob do for work"). */
    private val SLOT_CUES = mapOf(
        "work" to "work job jobs career study studies student school college uni university class major shift boss",
        "from" to "from country hometown born origin nationality",
        "lives" to "live lives living city town move moved",
        "game" to "game games play plays playing gaming gamer",
        "hobby" to "hobby hobbies weekend fun",
        "likes" to "like likes love loves favorite favourite fav food drink",
        "dislikes" to "hate hates dislike dislikes",
        "pet" to "pet pets cat cats dog dogs",
    ).mapValues { (_, v) -> v.split(' ').map { discordStem(it) }.toSet() }
    private val TYPE_ALIAS = mapOf(
        "job" to "work", "study" to "work", "school" to "work", "career" to "work", "occupation" to "work",
        "country" to "from", "hometown" to "from", "origin" to "from", "nationality" to "from",
        "city" to "lives", "location" to "lives", "home" to "lives", "live" to "lives",
        "games" to "game", "plays" to "game", "hobbies" to "hobby", "like" to "likes", "loves" to "likes", "taste" to "likes",
        "hates" to "dislikes", "dislike" to "dislikes", "pets" to "pet",
        "nickname" to "nick", "language" to "lang", "timezone" to "tz", "time zone" to "tz", "relationship" to "role",
    )
    /** The learner's type, normalised; "" when it isn't one we store. */
    fun slotOf(type: String): String {
        val t = type.lowercase().trim()
        val s = TYPE_ALIAS[t] ?: t
        return if (s in SLOTS || s in setOf("role", "nick", "lang", "tz")) s else ""
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun keyOf(id: String) = KEY_PREFIX + id

    fun load(ctx: Context, id: String): Card? {
        val raw = prefs(ctx).getString(keyOf(id), null) ?: return null
        return parse(id, raw)
    }

    private fun parse(id: String, raw: String): Card? = try {
        val o = JSONObject(raw)
        val notes = LinkedHashMap<String, List<String>>()
        o.optJSONObject("nt")?.let { n -> SLOTS.forEach { s -> strList(n.optJSONArray(s)).takeIf { it.isNotEmpty() }?.let { notes[s] = it } } }
        // A tentative note nobody confirmed within TENTATIVE_MS is gone.
        val now = System.currentTimeMillis()
        val unsure = HashMap<String, Long>()
        o.optJSONObject("un")?.let { u -> u.keys().forEach { k -> unsure[k] = u.optLong(k) } }
        unsure.entries.filter { now - it.value > DiscordBotLimits.TENTATIVE_MS }.forEach { e ->
            val (sl, v) = e.key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            notes[sl]?.let { l -> l.filterNot { norm(it) == v }.let { if (it.isEmpty()) notes.remove(sl) else notes[sl] = it } }
            unsure.remove(e.key)
        }
        Card(
            id = id,
            name = o.optString("n"),
            relationship = o.optString("rel").takeUnless { REL_TO_YOU.matches(it.trim()) }.orEmpty(),
            notes = notes,
            bits = strList(o.optJSONArray("b")),
            nicknames = strList(o.optJSONArray("nk")),
            preferredNick = o.optString("pn"),
            language = value(o.optString("lang")),
            alsoSpeaks = strList(o.optJSONArray("also")).map { value(it) }.filter { it.isNotBlank() },
            sentiment = o.optString("s"),
            howToTreat = o.optString("h").ifBlank { o.optString("rel").trim().takeIf { REL_TO_YOU.matches(it) }.orEmpty() },
            lastSeenMs = o.optLong("ls", 0L),
            interactions = o.optInt("ic", 0),
            pinned = o.optBoolean("p", false),
            avoid = strList(o.optJSONArray("av")),
            pronouns = o.optString("pro"),
            tz = o.optString("tz"),
            unsure = unsure,
        )
    } catch (_: Exception) { null }

    private fun strList(a: JSONArray?): List<String> =
        if (a == null) emptyList()
        else (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } }

    fun save(ctx: Context, card: Card) {
        val nt = JSONObject()
        card.notes.forEach { (k, v) -> if (v.isNotEmpty()) nt.put(k, JSONArray(v)) }
        val o = JSONObject()
            .put("n", card.name)
            .put("rel", card.relationship)
            .put("nt", nt)
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
            .put("av", JSONArray(card.avoid.takeLast(4)))
            .put("pro", card.pronouns)
            .put("tz", card.tz)
            .put("un", JSONObject().apply { card.unsure.forEach { (k, v) -> put(k, v) } })
        prefs(ctx).edit().putString(keyOf(card.id), o.toString()).apply()
    }

    /** Cardinal answered them (counts interactions). */
    fun touch(ctx: Context, id: String, name: String) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id)
        save(ctx, cur.copy(name = name.ifBlank { cur.name }, lastSeenMs = System.currentTimeMillis(), interactions = cur.interactions + 1))
    }

    /** They wrote something in chat (any message, to anyone). Creates a stub card for a first-timer. */
    fun seen(ctx: Context, id: String, name: String, atMs: Long) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id)
        save(ctx, cur.copy(name = cur.name.ifBlank { name.take(60) }, lastSeenMs = maxOf(cur.lastSeenMs, atMs)))
    }

    // ── guards ──
    private val POISON = Regex(
        "(?i)ignore (all|previous|the above)|system prompt|you are (now|an|a )|disregard|" +
        "new instructions|forget (everything|your)|jailbreak|pretend to be"
    )
    private val NONE_VALUE = Regex("(?i)^(none|n/?a|null|nil|unknown|not (specified|mentioned|sure|clear|known)|nothing|no|-+|\\?+|same|unchanged)\\.?$")
    private fun value(s: String?): String = s?.trim()?.takeUnless { NONE_VALUE.matches(it) }
        ?.takeIf { v -> v.any { it.isLetterOrDigit() } && !v.startsWith("[") && !v.startsWith("{") }.orEmpty()
    // How they relate to Cardinal ("friend", "rival") isn't a server role: it goes under "with them".
    private val REL_TO_YOU = Regex("(?i)^(a |an |the |his |their |cardinal'?s )?(close |good |best |old |new )?(friend|bestie|buddy|pal|homie|bff|mate|acquaintance|rival|enemy|nemesis|frenemy|crush)s?( of (cardinal|yours))?\\.?$")
    private val GENERIC_REL = Regex("(?i)^(a |an |the )?(regular |server |discord |normal )?(member|user|participant|person|chatter|someone|human|guy|people)s?\\.?$")
    private fun cleanNick(s: String, ownNames: Set<String>): String? {
        val t = s.trim()
        if (t.length < 2 || t.length > 32) return null
        if (POISON.containsMatchIn(t)) return null
        if (ownNames.any { t.equals(it, true) }) return null
        return t
    }

    // ── value matching ──
    private fun norm(s: String): String =
        Regex("[^\\p{L}\\p{N} ]").replace(s.lowercase(), " ").replace(Regex("\\s+"), " ").trim()
    private val VALUE_FILLER = setOf("a", "an", "the", "of", "at", "in", "on", "my", "their", "his", "her", "for", "to", "and", "with")
    private fun valueWords(s: String): Set<String> = norm(s).split(' ')
        .filter { it.isNotBlank() && it !in VALUE_FILLER }.map { discordStem(it) }.toSet()
    /** Same thing said twice: equal words, or one's words inside the other's. */
    private fun sameValue(a: String, b: String): Boolean {
        val x = valueWords(a); val y = valueWords(b)
        if (x.isEmpty() || y.isEmpty()) return norm(a) == norm(b)
        return x.containsAll(y) || y.containsAll(x)
    }

    /**
     * Store one checked note. Returns true when the card changed. A single slot is replaced; a list slot
     * appends (the same thing said twice keeps the shorter wording, the main thing), dropping the oldest
     * past its cap. A like/dislike about the same thing flips the other one out.
     */
    fun addNote(ctx: Context, id: String, name: String, slot: String, raw: String, confirm: Boolean = false): Boolean {
        if (id.isBlank() || slot !in SLOTS) return false
        var v = value(raw).replace(EXAMPLE, "").trim().trimEnd('.', '!', ',', ':', ';').take(60)
        // "Straftat's game" / "Valorant game" → the name itself (the slot already says it's a game).
        if (slot in setOf("game", "hobby", "likes", "dislikes")) v = v.replace(Regex("(?i)^(the )?(video ?)?games? "), "")
            .replace(Regex("(?i)('s)?\\s+(video ?)?games?$"), "").trim().ifBlank { v }
        if (v.isBlank() || POISON.containsMatchIn(v)) return false
        val now = System.currentTimeMillis()
        val cur = load(ctx, id) ?: Card(id = id, name = name.take(60))
        val notes = LinkedHashMap(cur.notes)
        val unsure = HashMap(cur.unsure)
        val list = notes[slot].orEmpty()
        val i = list.indexOfFirst { sameValue(it, v) }
        if (i >= 0) {
            // Known already: said again in a later conversation (or by a second person) = confirmed.
            val key = "$slot|${norm(list[i])}"
            val first = unsure[key] ?: return false
            if (!confirm && now - first < DiscordBotLimits.CONFIRM_GAP_MS) return false
            unsure.remove(key)
            save(ctx, cur.copy(unsure = unsure)); return true
        }
        val next: List<String> = if (slot in SINGLE) {
            list.forEach { unsure.remove("$slot|${norm(it)}") }   // a real update ("moved to vancouver")
            listOf(v)
        } else if (list.size < (CAP[slot] ?: 4)) list + v
        else {
            // Full: only a tentative note makes room; confirmed ones are never pushed out for a newer one.
            val drop = list.indexOfFirst { "$slot|${norm(it)}" in unsure }
            if (drop < 0) return false
            unsure.remove("$slot|${norm(list[drop])}")
            list.filterIndexed { k, _ -> k != drop } + v
        }
        if (!confirm) unsure["$slot|${norm(v)}"] = now
        notes[slot] = next
        val flip = when (slot) { "likes" -> "dislikes"; "dislikes" -> "likes"; else -> null }
        if (flip != null) notes[flip] = notes[flip].orEmpty().filterNot { sameValue(it, v) }
        save(ctx, cur.copy(name = cur.name.ifBlank { name.take(60) }, notes = notes, unsure = unsure))
        return true
    }

    fun isUnsure(card: Card, slot: String, v: String) = "$slot|${norm(v)}" in card.unsure

    /** Their own later line says a tentative note again ("yeah i'm a nurse"): confirmed. Free. */
    fun confirmSaid(ctx: Context, id: String, lines: List<String>): Int {
        val cur = load(ctx, id) ?: return 0
        if (cur.unsure.isEmpty()) return 0
        val now = System.currentTimeMillis()
        val said = lines.map { valueWords(it) }
        val hit = cur.unsure.filter { (k, first) ->
            now - first >= DiscordBotLimits.CONFIRM_GAP_MS && valueWords(k.substringAfter('|')).let { w ->
                w.isNotEmpty() && said.any { l -> w.count { it in l } * 3 >= w.size * 2 } }
        }.keys
        if (hit.isEmpty()) return 0
        save(ctx, cur.copy(unsure = cur.unsure - hit))
        return hit.size
    }

    /** They answered Cardinal's check ("you're a nurse, right?") with yes / no: confirm or drop that note. */
    fun answerCheck(ctx: Context, id: String, yes: Boolean, botLine: String): Boolean {
        val cur = load(ctx, id) ?: return false
        val said = valueWords(botLine)
        val k = cur.unsure.keys.firstOrNull { key -> valueWords(key.substringAfter('|')).let { w ->
            w.isNotEmpty() && w.count { it in said } * 3 >= w.size * 2 } } ?: return false
        if (yes) { save(ctx, cur.copy(unsure = cur.unsure - k)); return true }
        val (sl, v) = k.split('|', limit = 2).let { it[0] to it[1] }
        val kept = cur.notes[sl].orEmpty().filterNot { norm(it) == v }
        save(ctx, cur.copy(unsure = cur.unsure - k, notes = LinkedHashMap(cur.notes).apply { if (kept.isEmpty()) remove(sl) else put(sl, kept) }))
        return true
    }

    // "(e.g. "may the wind bless…")", "(like …)", "("…")" — an example, not the note.
    private val EXAMPLE = Regex("(?i)\\s*[(\\[](?:e\\.?\\s?g\\.?|eg|ex\\.?|i\\.?\\s?e\\.?|like|such as|for example|including|\"|'|“)[^)\\]]*[)\\]]?|\\s*[-,:]\\s*(?:e\\.?g\\.?|like|such as)\\s+[\"“].*$")

    /** Remove a note (by slot + value, loose match); "i quit my bakery job" / a correction. */
    fun removeNote(ctx: Context, id: String, slot: String, raw: String, force: Boolean = false): Boolean {
        val cur = load(ctx, id) ?: return false
        if (cur.pinned && !force) return false
        val list = cur.notes[slot] ?: return false
        val kept = list.filterNot { it.equals(raw, true) || sameValue(it, raw) }
        if (kept.size == list.size) return false
        save(ctx, cur.copy(notes = LinkedHashMap(cur.notes).apply { if (kept.isEmpty()) remove(slot) else put(slot, kept) },
            unsure = cur.unsure.filterKeys { k -> !k.startsWith("$slot|") || kept.any { "$slot|${norm(it)}" == k } }))
        return true
    }

    fun setTz(ctx: Context, id: String, name: String, tz: String, overwrite: Boolean) {
        if (id.isBlank() || tz.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id, name = name.take(60))
        if (cur.tz == tz || (!overwrite && cur.tz.isNotBlank())) return
        save(ctx, cur.copy(tz = tz, name = cur.name.ifBlank { name.take(60) }))
    }

    /** "work: bank teller" rows, slot order — for corrections, admin, search. */
    fun factLines(card: Card): List<String> =
        SLOTS.flatMap { s -> card.notes[s].orEmpty().map { "$s: $it" } }

    /** Every note of a card as "work: bank teller, cashier · plays: Valorant" (compact, slot order). */
    private fun renderSlots(card: Card, slots: Collection<String>): String =
        SLOTS.filter { it in slots && card.notes[it].orEmpty().isNotEmpty() }
            .joinToString(" · ") { s -> (if (s == "about") "" else LABEL[s] + ": ") + card.notes[s]!!.joinToString(", ") { v -> v + if (isUnsure(card, s, v)) " (?)" else "" } }

    private fun stemmedWords(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { discordStem(it.value) }.filter { it.length >= 3 }.toSet()

    /** Slots the message touches: a value word or a slot cue word ("job", "play") matches. */
    private fun relevantSlots(card: Card, keywords: Set<String>): List<String> {
        if (keywords.isEmpty()) return emptyList()
        val want = keywords.map { discordStem(it) }.toSet()
        return SLOTS.filter { s ->
            val vals = card.notes[s].orEmpty(); vals.isNotEmpty() &&
                (vals.any { v -> stemmedWords(v).any { it in want } } || SLOT_CUES[s].orEmpty().any { it in want })
        }
    }

    /** The live offset + their local time now ("UTC-4, 23:10 for them"), or "". */
    fun tzLine(card: Card, nowMs: Long = System.currentTimeMillis()): String {
        val z = TimezoneGuess.zoneOf(card.tz) ?: return ""
        val t = java.time.Instant.ofEpochMilli(nowMs).atZone(z)
        return TimezoneGuess.offsetLabel(z, nowMs) + ", " + String.format("%02d:%02d", t.hour, t.minute) + " for them"
    }

    // ── corrections ──
    /**
     * What's stored about these people, as (id, name, item) rows for the learner's numbered correction
     * view: each note ("work: bank teller"), plus "goes by X" per nickname. Only built when the chat has a correction.
     */
    fun correctionItems(ctx: Context, ids: Collection<String>, maxPeople: Int = 6): List<Triple<String, String, String>> =
        ids.distinct().mapNotNull { load(ctx, it) }.filter { !it.pinned }
            .filter { it.notes.isNotEmpty() || it.nicknames.isNotEmpty() || it.preferredNick.isNotBlank() }
            .take(maxPeople)
            .flatMap { c ->
                val nicks = (listOf(c.preferredNick) + c.nicknames).filter { it.isNotBlank() && !it.equals(c.name, true) }
                    .distinctBy { it.lowercase() }
                factLines(c).takeLast(8).map { Triple(c.id, c.name, it) } + nicks.map { Triple(c.id, c.name, "goes by $it") }
            }

    /** The chat said this stored item is wrong / unwanted: drop the note, or stop using the nickname. */
    fun forgetItem(ctx: Context, id: String, item: String) {
        val cur = load(ctx, id) ?: return
        if (cur.pinned) return
        if (item.startsWith("goes by ")) {
            val nick = item.removePrefix("goes by ").trim()
            save(ctx, cur.copy(
                nicknames = cur.nicknames.filterNot { it.equals(nick, true) },
                preferredNick = cur.preferredNick.takeUnless { it.equals(nick, true) }.orEmpty(),
            ))
        } else {
            val slot = item.substringBefore(':').trim()
            if (slot in SLOTS) removeNote(ctx, id, slot, item.substringAfter(':').trim())
        }
    }

    /** "bob: work: bank teller · plays: Valorant" per person — so the learner only adds what's new. */
    fun knownFactsLine(ctx: Context, ids: Collection<String>): String =
        ids.distinct().mapNotNull { load(ctx, it) }.filter { it.notes.isNotEmpty() && it.name.isNotBlank() }
            .joinToString("\n") { c -> c.name + ": " + renderSlots(c, SLOTS) }

    // ── the few non-slot updates (nickname, preferred name, role, language, stop-requests) ──
    /**
     * Recognised keys: `nickname`, `preferredName`, `relationship`, `language`, `sentiment`, and — only when
     * [correcting] — `notNickname`, `avoid`. Roles are filled once and kept (replaced only in correction mode).
     */
    fun applyDelta(ctx: Context, id: String, name: String, delta: JSONObject, correcting: Boolean = false) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id)
        val ownNames = setOf(name, cur.name).filter { it.isNotBlank() }.toSet()
        val incomingNick = cleanNick(value(delta.optString("nickname")), ownNames)
        val notNick = if (correcting) value(delta.optString("notNickname")).lowercase() else ""
        val nicks = (cur.nicknames + listOfNotNull(incomingNick)).distinctBy { it.lowercase() }
            .filterNot { notNick.isNotBlank() && it.equals(notNick, true) }
        val preferred = (cleanNick(value(delta.optString("preferredName")), ownNames) ?: cur.preferredNick)
            .takeUnless { notNick.isNotBlank() && it.equals(notNick, true) }.orEmpty()
        val avoidNew = if (correcting) value(delta.optString("avoid")).take(100).takeIf { it.isNotBlank() && !POISON.containsMatchIn(it) } else null
        val avoid = (cur.avoid + listOfNotNull(avoidNew) + listOfNotNull(notNick.takeIf { it.isNotBlank() }?.let { "calling them \"$it\"" }))
            .distinctBy { norm(it) }.takeLast(4)
        val incomingLang = value(delta.optString("language")).take(24)
        val langs = spokenLanguages(cur)
        val (lang, also) = when {
            incomingLang.isBlank() || langs.any { it.equals(incomingLang, true) } -> cur.language to cur.alsoSpeaks
            cur.language.isBlank() -> incomingLang to cur.alsoSpeaks
            else -> cur.language to (cur.alsoSpeaks + incomingLang)
        }
        val rawRel = value(delta.optString("relationship")).take(80).takeUnless { GENERIC_REL.matches(it) || POISON.containsMatchIn(it) }.orEmpty()
        val incomingRel = rawRel.takeUnless { REL_TO_YOU.matches(it) }.orEmpty()
        val withThem = cur.howToTreat.ifBlank { rawRel.takeIf { REL_TO_YOU.matches(it) }.orEmpty() }
        val relationship = if (correcting && incomingRel.isNotBlank() && !cur.pinned) incomingRel else cur.relationship.ifBlank { incomingRel }
        save(ctx, cur.copy(
            name = cur.name.ifBlank { name.take(60) },
            nicknames = nicks,
            preferredNick = preferred,
            language = lang,
            alsoSpeaks = also,
            sentiment = value(delta.optString("sentiment")).take(60).ifBlank { cur.sentiment },
            relationship = relationship,
            howToTreat = withThem,
            avoid = avoid,
        ))
    }

    /** The name Cardinal should address this person by: their PREFERRED nick, else display name. */
    fun addressName(card: Card): String = card.preferredNick.ifBlank { card.name.ifBlank { "them" } }

    // ── prompt rendering ──────────────────────────────────────────────────

    /** A rendered person line + whether it carries a nickname/alias (→ the one-name rule applies). */
    data class PromptLine(val text: String, val hasNick: Boolean)

    /** "real name (pronouns x; goes by nick; also: a, b)". */
    private fun nameHeader(card: Card, fallbackName: String): Pair<String, Boolean> {
        val real = card.name.ifBlank { fallbackName.ifBlank { card.preferredNick.ifBlank { "them" } } }
        val casual = card.preferredNick.takeIf { it.isNotBlank() && !it.equals(real, true) }
        val aliases = card.nicknames.filter { !it.equals(real, true) && !it.equals(casual ?: "", true) }.take(2)
        val parts = listOfNotNull(
            card.pronouns.takeIf { it.isNotBlank() }?.let { "pronouns $it" },
            casual?.let { "goes by $it" },
            aliases.takeIf { it.isNotEmpty() }?.let { "also: " + it.joinToString(", ") },
        )
        return (real + if (parts.isNotEmpty()) " (" + parts.joinToString("; ") + ")" else "") to (casual != null || aliases.isNotEmpty())
    }

    /** Full card for the lab/admin views. */
    fun renderForPrompt(card: Card, keywords: Set<String>, full: Boolean = false): String {
        val (header, _) = nameHeader(card, "")
        val sb = StringBuilder(header)
        if (card.relationship.isNotBlank()) sb.append(" — ").append(card.relationship)
        val langs = spokenLanguages(card)
        if (langs.isNotEmpty()) sb.append("\n  speaks: ").append(langs.joinToString(", "))
        tzLine(card).takeIf { it.isNotBlank() }?.let { sb.append("\n  time: ").append(it) }
        val slots = if (full) SLOTS else relevantSlots(card, keywords)
        renderSlots(card, slots).takeIf { it.isNotBlank() }?.let { sb.append("\n  ").append(it) }
        if (card.avoid.isNotEmpty()) sb.append("\n  asked you to stop: ").append(card.avoid.joinToString("; "))
        return sb.toString().take(DiscordBotLimits.USER_CARD_MAX_CHARS)
    }

    /**
     * The person Cardinal is answering — always included: name/pronouns/nickname, role, how to treat them, a
     * non-English language, then only the slots this message touches. [recall] = they asked what Cardinal
     * knows about them → every slot. [withTime] = a time question → their local time.
     */
    fun answeringLine(ctx: Context, id: String, fallbackName: String, keywords: Set<String>, recall: Boolean, withTime: Boolean = false): PromptLine {
        val card = load(ctx, id) ?: return PromptLine(fallbackName, false)
        val (header, hasNick) = nameHeader(card, fallbackName)
        val sb = StringBuilder(header)
        if (card.relationship.isNotBlank()) sb.append(" — ").append(card.relationship.trim().trimEnd('.'))
        sb.append('.')
        if (card.howToTreat.isNotBlank()) sb.append(" With them: ").append(card.howToTreat.trim().trimEnd('.')).append('.')
        if (card.avoid.isNotEmpty()) sb.append(" Quietly never do this with them again (don't mention it): ").append(card.avoid.joinToString("; ") { it.trim().trimEnd('.') }).append('.')
        val langs = spokenLanguages(card)
        if (langs.isNotEmpty() && langs.none { it.equals("english", true) })
            sb.append(" Speaks ").append(langs.joinToString(", ")).append(" (not English).")
        if (withTime || recall) tzLine(card).takeIf { it.isNotBlank() }?.let { sb.append(" Their time: ").append(it).append('.') }
        val slots = if (recall) SLOTS else relevantSlots(card, keywords)
        renderSlots(card, slots).takeIf { it.isNotBlank() }?.let { sb.append(if (recall) " Known: " else " Known (use only if it fits): ").append(it).append('.') }
        val want = keywords.map { discordStem(it) }.toSet()
        if (card.bits.isNotEmpty() && (recall || card.bits.any { b -> stemmedWords(b).any { it in want } }))
            sb.append(" Running bit with them: ").append(card.bits.last()).append('.')
        return PromptLine(sb.toString(), hasNick)
    }

    /**
     * Someone else in (or named in) the conversation. Named people come with what's relevant, then their other
     * slots up to [namedLimit] slots; someone merely talking nearby only when there's a nickname or a relevant
     * slot. [full] = they were asked about → every slot. Null = leave them out.
     */
    fun otherLine(
        ctx: Context, id: String, fallbackName: String, keywords: Set<String>, full: Boolean, namedLimit: Int = 0, withTime: Boolean = false,
    ): PromptLine? {
        val card = load(ctx, id) ?: return null
        val (header, hasNick) = nameHeader(card, fallbackName)
        val relevant = relevantSlots(card, keywords)
        val slots = when {
            full -> SLOTS
            namedLimit > 0 -> (relevant + SLOTS.filter { card.notes[it].orEmpty().isNotEmpty() }).distinct().take(namedLimit)
            else -> relevant.take(2)
        }
        val rel = card.relationship.trim().trimEnd('.')
        val time = if (withTime || full) tzLine(card) else ""
        val body = renderSlots(card, slots)
        if (!full && !hasNick && body.isBlank() && time.isBlank() && (namedLimit == 0 || rel.isBlank())) return null
        val sb = StringBuilder("- ").append(header)
        if (rel.isNotBlank() && (full || namedLimit > 0 || body.isNotBlank() || hasNick)) sb.append(" — ").append(rel)
        if (body.isNotBlank()) sb.append(": ").append(body)
        if (time.isNotBlank()) sb.append(" (time: ").append(time).append(')')
        return PromptLine(sb.toString(), hasNick)
    }

    // ── compact reply-prompt rendering ──
    /** `alice "ali" (she/her, UTC-4, 10:32 for her)` — name, what he calls them, pronouns, timezone. */
    fun personHeader(card: Card?, fallbackName: String, localTime: Boolean, nowMs: Long = System.currentTimeMillis()): Pair<String, Boolean> {
        if (card == null) return fallbackName to false
        val real = card.name.ifBlank { fallbackName.ifBlank { card.preferredNick.ifBlank { "them" } } }
        val nicks = (listOf(card.preferredNick) + card.nicknames).map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(real, true) }.distinctBy { it.lowercase() }.take(3)
        val zone = TimezoneGuess.zoneOf(card.tz)
        val bits = ArrayList<String>()
        if (card.pronouns.isNotBlank()) bits.add(card.pronouns)
        if (zone != null) {
            bits.add(TimezoneGuess.offsetLabel(zone, nowMs))
            if (localTime) {
                val t = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
                val obj = card.pronouns.split('/').getOrNull(1)?.trim()?.takeIf { it.matches(Regex("[a-zA-Z]{2,6}")) } ?: "them"
                bits.add(String.format("%02d:%02d for %s", t.hour, t.minute, obj))
            }
        }
        val sb = StringBuilder(real)
        if (nicks.isNotEmpty()) sb.append(' ').append(nicks.joinToString("/") { "\"$it\"" })
        if (bits.isNotEmpty()) sb.append(" (").append(bits.joinToString(", ")).append(')')
        return sb.toString() to nicks.isNotEmpty()
    }

    /** "Talking to:" — the person he's replying to, with everything he knows about them, one thing per line. */
    fun talkingTo(ctx: Context, id: String, fallbackName: String, localTime: Boolean, extra: String = "", langAsk: Boolean = false,
                  keywords: Set<String> = emptySet(), all: Boolean = false): PromptLine {
        val card = load(ctx, id)
        val (header, hasNick) = personHeader(card, fallbackName, localTime)
        val sb = StringBuilder("Talking to: ").append(header)
        if (extra.isNotBlank()) sb.append(' ').append(extra)

        if (card != null) {
            if (card.relationship.isNotBlank()) sb.append("\n- server role: ").append(card.relationship.trim().trimEnd('.'))
            if (card.howToTreat.isNotBlank()) sb.append("\n- with them: ").append(card.howToTreat.trim().trimEnd('.'))
            val langs = spokenLanguages(card)
            // English is the default: languages show when there's another one, or they're asking about languages.
            if (langs.isNotEmpty() && (langAsk || langs.any { !it.equals("english", true) })) sb.append("\n- speaks: ").append(langs.joinToString(", "))
            // Everything known rides along, but only what fits this message reads as usable: the rest is background
            // ("plays JJ's on PC" kept turning up in a food answer when every note looked equally relevant).
            val rel = if (all) SLOTS else relevantSlots(card, keywords)
            val back = ArrayList<String>()
            for (sl in SLOTS) card.notes[sl].orEmpty().takeIf { it.isNotEmpty() }?.let {
                val vals = it.joinToString(", ") { v -> v + if (isUnsure(card, sl, v)) " (?)" else "" }
                if (sl in rel) sb.append("\n- ").append(LABEL[sl]).append(": ").append(vals)
                else back.add(LABEL[sl] + " " + vals)
            }
            if (back.isNotEmpty()) sb.append("\n- background (don't bring up unless asked): ").append(back.joinToString("; "))
            if (card.unsure.isNotEmpty()) sb.append("\n(?) = not sure yet: check it with them before stating it")
            card.bits.lastOrNull()?.let { sb.append("\n- running bit: ").append(it) }
            if (card.avoid.isNotEmpty()) sb.append("\n- never (silently): ").append(card.avoid.joinToString("; ") { it.trim().trimEnd('.') })
        }
        return PromptLine(sb.toString(), hasNick)
    }

    /**
     * One "Others:" line: always who they are (nickname, pronouns, timezone); their notes when [full] (named,
     * replied to, in the current back-and-forth) or when a note matches the conversation. Null when nothing is known.
     */
    fun otherPerson(ctx: Context, id: String, fallbackName: String, keywords: Set<String>, full: Boolean, localTime: Boolean, langAsk: Boolean = false): PromptLine? {
        val card = load(ctx, id) ?: return null
        val (header, hasNick) = personHeader(card, fallbackName, localTime)
        val slots = if (full) SLOTS else relevantSlots(card, keywords)
        val notes = ArrayList<String>()
        card.relationship.trim().trimEnd('.').takeIf { it.isNotBlank() }?.let { notes.add(if (Regex("(?i)\\b(server|role)\\b").containsMatchIn(it)) it else "server role $it") }
        if (full && card.howToTreat.isNotBlank()) notes.add("with them: " + card.howToTreat.trim().trimEnd('.'))
        // Languages ride with the full card ("what languages does X speak?" had nothing to answer from).
        if (full) spokenLanguages(card).takeIf { l -> l.isNotEmpty() && (langAsk || l.any { !it.equals("english", true) }) }
            ?.let { notes.add("speaks " + it.joinToString(", ")) }
        SLOTS.filter { it in slots }.forEach { sl -> card.notes[sl].orEmpty().takeIf { it.isNotEmpty() }?.let {
            notes.add((if (sl == "about") "" else LABEL[sl] + " ") + it.joinToString(", ") { v -> v + if (isUnsure(card, sl, v)) " (?)" else "" }) } }
        val basic = header != card.name || hasNick
        if (notes.isEmpty() && !basic) return null
        return PromptLine("- " + header + if (notes.isNotEmpty()) ": " + notes.joinToString("; ") else "", hasNick)
    }

    /** "Who …?" with nobody named ("who works at a bank?"): the cards whose notes/role best match. */
    fun searchCards(ctx: Context, keywords: Set<String>, exclude: Set<String>, max: Int): List<String> {
        val want = keywords.map { discordStem(it) }.toSet(); if (want.isEmpty()) return emptyList()
        return list(ctx).asSequence()
            .filter { it.id !in exclude }
            .map { c -> c.id to stemmedWords(factLines(c).joinToString(" ") + " " + c.relationship + " " + c.bits.joinToString(" ")).count { it in want } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(max).map { it.first }.toList()
    }

    // ── nickname-aware resolution ──
    /** One name a person goes by: [isRealName] = their display name (vs a nickname/preferred name). */
    data class NameKey(val key: String, val id: String, val isRealName: Boolean)

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

    /** Admin search: name, id, nicknames, notes, role, language. */
    fun search(ctx: Context, query: String): List<Card> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return list(ctx)
        return list(ctx).filter { c ->
            c.name.lowercase().contains(q) || c.id.contains(q) || c.preferredNick.lowercase().contains(q) ||
                c.nicknames.any { it.lowercase().contains(q) } || factLines(c).any { it.lowercase().contains(q) } ||
                c.relationship.lowercase().contains(q) || spokenLanguages(c).any { it.lowercase().contains(q) }
        }
    }

    fun count(ctx: Context): Int = prefs(ctx).all.keys.count { it.startsWith(KEY_PREFIX) }

    fun delete(ctx: Context, id: String) { prefs(ctx).edit().remove(keyOf(id)).apply() }

    fun setPinned(ctx: Context, id: String, pinned: Boolean) {
        val cur = load(ctx, id) ?: return
        save(ctx, cur.copy(pinned = pinned))
    }

    /** Admin edit of the free-text fields. */
    fun editFields(ctx: Context, id: String, relationship: String, howToTreat: String, preferredNick: String) {
        val cur = load(ctx, id) ?: return
        save(ctx, cur.copy(relationship = relationship.trim(), howToTreat = howToTreat.trim(), preferredNick = preferredNick.trim()))
    }

    fun clearTz(ctx: Context, id: String) { load(ctx, id)?.let { save(ctx, it.copy(tz = "")) } }
    fun clearPronouns(ctx: Context, id: String) { load(ctx, id)?.let { save(ctx, it.copy(pronouns = "")) } }
    fun removeLanguage(ctx: Context, id: String, lang: String) {
        val c = load(ctx, id) ?: return
        val left = spokenLanguages(c).filterNot { it.equals(lang, true) }
        save(ctx, c.copy(language = left.firstOrNull().orEmpty(), alsoSpeaks = left.drop(1)))
    }
    fun removeNick(ctx: Context, id: String, nick: String) {
        val c = load(ctx, id) ?: return
        save(ctx, c.copy(nicknames = c.nicknames.filterNot { it.equals(nick, true) },
            preferredNick = c.preferredNick.takeUnless { it.equals(nick, true) }.orEmpty()))
    }
    fun removeAvoid(ctx: Context, id: String, a: String) { load(ctx, id)?.let { save(ctx, it.copy(avoid = it.avoid - a)) } }

    /**
     * A note from free text — "work: bank teller" (typed), or plain text sorted into a slot by its lead words
     * ("plays Valorant" → game, "lives in Toronto" → lives). Used by the admin teach box and the lab seed.
     */
    fun noteFromText(ctx: Context, id: String, name: String, text: String, pin: Boolean = false): Boolean {
        val (slot, v) = guessSlot(text) ?: return false
        val ok = addNote(ctx, id, name, slot, v, confirm = true)
        if (pin) setPinned(ctx, id, true)
        return ok
    }

    private val GUESS = listOf(
        Regex("(?i)^(?:is |are )?from\\s+(.+)$") to "from",
        Regex("(?i)^(?:lives|living|is living) in\\s+(.+)$") to "lives",
        Regex("(?i)^(?:plays|is playing)\\s+(.+)$") to "game",
        Regex("(?i)^(?:works|is working) (?:as |at |in )?(.+)$") to "work",
        Regex("(?i)^(?:studies|is studying|learning)\\s+(.+)$") to "work",
        Regex("(?i)^(?:has|owns) (?:an? )?(?:\\w+ )?((?:cat|dog|kitten|puppy|parrot|bird|snake|rabbit|hamster|ferret|fish)\\b.*)$") to "pet",
        Regex("(?i)^(?:really )?(?:likes|loves|enjoys|is into|really into|is a fan of)\\s+(.+)$") to "likes",
        Regex("(?i)^(?:hates|dislikes|can'?t stand)\\s+(.+)$") to "dislikes",
        Regex("(?i)^listens to\\s+(.+)$") to "likes",
        Regex("(?i)^is an? ((?:professional )?(?:\\w+ )?(?:chef|nurse|teacher|developer|programmer|engineer|artist|student|doctor|firefighter|baker|driver|designer|streamer|moderator|mod))$") to "work",
    )
    fun guessSlot(text: String): Pair<String, String>? {
        val t = text.trim(); if (t.isBlank()) return null
        Regex("^(\\w[\\w ]{1,12}):\\s*(.+)$").find(t)?.let { m ->
            val s = slotOf(m.groupValues[1]); if (s in SLOTS) return s to m.groupValues[2].trim()
        }
        for ((re, s) in GUESS) re.find(t)?.let { return s to it.groupValues[1].trim() }
        return "about" to t
    }

    /** Every language they've been seen writing (main first). */
    fun spokenLanguages(card: Card): List<String> =
        (listOf(card.language) + card.alsoSpeaks).map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }

    /** A language seen in their own message (free detection, English included). */
    fun noteLanguage(ctx: Context, id: String, name: String, lang: String): Boolean {
        val l = lang.trim().take(24)
        if (id.isBlank() || l.isBlank()) return true
        val cur = load(ctx, id) ?: Card(id = id, name = name.take(60))
        if (spokenLanguages(cur).any { it.equals(l, true) }) return true
        save(ctx, if (cur.language.isBlank()) cur.copy(language = l) else cur.copy(alsoSpeaks = cur.alsoSpeaks + l))
        return true
    }

    /** Set from the person's own message only ("my pronouns are she/her"); "" clears them. */
    fun setPronouns(ctx: Context, id: String, name: String, pronouns: String) {
        if (id.isBlank()) return
        val cur = load(ctx, id) ?: Card(id = id, name = name.take(60))
        if (cur.pronouns == pronouns) return
        save(ctx, cur.copy(pronouns = pronouns.take(40), name = cur.name.ifBlank { name.take(60) }))
    }

    fun pronounsOf(ctx: Context, id: String): String = load(ctx, id)?.pronouns.orEmpty()
}
