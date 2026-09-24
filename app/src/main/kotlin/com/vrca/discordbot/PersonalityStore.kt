package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cardinal's SELF — a self-grown identity, no admin persona. The only fixed scaffolding is
 * the two-sentence [ANCHOR]; everything else emerges from watching the room and mutates on
 * three timescales:
 *
 *  - **traits** (slow, weeks) — weighted quirks/opinions the learn pass sees in his own messages;
 *    reinforced each time they show again, faded when they stop showing, near-duplicates merged,
 *    and a new trait always finds a slot (replacing a never-established one) so he keeps
 *    developing instead of freezing.
 *  - **mood** (hours) — one short line, changed at most every [DiscordBotLimits.MOOD_MIN_INTERVAL_MS].
 *  - **style** / **episodes** — optional admin-taught lines (the learner doesn't write them).
 *
 * Rendered into the reply prompt as a compact digest ([snapshot]). Plain SharedPreferences
 * (not a secret). Admin can view/reset and PIN a trait (protect it from decay) or TEACH one
 * (inject at max strength) — but never type a persona; the AI owns its voice.
 */
object PersonalityStore {
    private const val PREFS = "vrca_discord_personality"
    private const val KEY_STYLE = "style"
    private const val KEY_TRAITS = "traits"
    private const val KEY_MOOD = "mood"
    private const val KEY_EPISODES = "episodes"
    private const val KEY_LAST_DECAY = "last_decay"
    private const val KEY_MOOD_AT = "mood_at"

    /** The ONLY fixed identity line (the reply prompt's core builds on it). Voice is learned, not decreed. */
    const val ANCHOR = "You're Cardinal, a member of this Discord."

    private const val START_STRENGTH = 2
    private const val MAX_STRENGTH = 10
    // Each time a trait shows again it grows by 1; a trait that stops showing loses 1 per fade
    // interval, so an occasionally-shown trait still settles while a one-off fades out.
    private const val REINFORCE_INLINE = 1
    private const val DECAY = 1

    /** [lastMs] = when this trait was last proposed/reinforced (0 = unknown, older data). */
    data class Trait(val text: String, val strength: Int, val pinned: Boolean = false, val lastMs: Long = 0L)
    data class Self(
        val style: List<String>,
        val traits: List<Trait>,
        val mood: String,
        val episodes: List<String>,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): Self {
        val p = prefs(ctx)
        return Self(
            style = readStrings(p.getString(KEY_STYLE, null)),
            traits = readTraits(p.getString(KEY_TRAITS, null)),
            mood = p.getString(KEY_MOOD, "").orEmpty(),
            episodes = readStrings(p.getString(KEY_EPISODES, null)),
        )
    }

    private fun readStrings(raw: String?): List<String> = try {
        if (raw.isNullOrBlank()) emptyList()
        else JSONArray(raw).let { a -> (0 until a.length()).mapNotNull { a.optString(it).trim().ifBlank { null } } }
    } catch (_: Exception) { emptyList() }

    private fun readTraits(raw: String?): List<Trait> = try {
        if (raw.isNullOrBlank()) emptyList()
        else JSONArray(raw).let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t").trim()
                if (t.isBlank()) null
                else Trait(t, o.optInt("s", 1).coerceIn(1, MAX_STRENGTH), o.optBoolean("p", false), o.optLong("r", 0L))
            }
        }
    } catch (_: Exception) { emptyList() }

    // Process-global digest cache (one bot). Invalidated on every write so it can't go stale;
    // saves re-parsing the personality JSON on the hot reply path.
    @Volatile private var cachedDigest: String? = null

    private fun save(ctx: Context, self: Self) {
        cachedDigest = null
        val traitArr = JSONArray()
        self.traits.forEach {
            traitArr.put(JSONObject().put("t", it.text).put("s", it.strength).put("p", it.pinned).put("r", it.lastMs))
        }
        prefs(ctx).edit()
            .putString(KEY_STYLE, JSONArray(self.style).toString())
            .putString(KEY_TRAITS, traitArr.toString())
            .putString(KEY_MOOD, self.mood)
            .putString(KEY_EPISODES, JSONArray(self.episodes).toString())
            .apply()
    }

    /**
     * Compact digest injected into the reply prompt: mood + the strongest traits (pinned first) + a
     * couple of speech habits / remembered moments, as short `;`-joined runs. Built from WHOLE items
     * up to [DiscordBotLimits.SELF_DIGEST_MAX_CHARS] — never cut mid-trait. Blank until evolved.
     */
    fun snapshot(ctx: Context): String {
        cachedDigest?.let { return it }
        val s = load(ctx)
        val sb = StringBuilder()
        if (s.mood.isNotBlank()) sb.append("Mood: ").append(s.mood.trim().trimEnd('.')).append('.')
        // EVERY trait, strongest first (so he never strays from who he's become); each is a short phrase.
        val traits = s.traits.sortedWith(
            compareByDescending<Trait> { (if (it.pinned) 100 else 0) + it.strength }.thenByDescending { it.lastMs }
        ).map { it.text.trim().trimEnd('.') }
        if (traits.isNotEmpty()) sb.append(if (sb.isEmpty()) "" else " ").append("Traits: ").append(traits.joinToString("; ")).append('.')
        // Admin-taught extras (rare) stay bounded.
        val extras = ArrayList<String>()
        if (s.style.isNotEmpty()) extras.add("How you talk: ${s.style.take(3).joinToString("; ") { it.trim().trimEnd('.') }}.")
        if (s.episodes.isNotEmpty()) extras.add("You remember: ${s.episodes.takeLast(2).joinToString("; ") { it.trim().trimEnd('.') }}.")
        for (p in extras) {
            if (sb.length + p.length + 1 > DiscordBotLimits.SELF_DIGEST_MAX_CHARS + traits.sumOf { it.length }) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(p)
        }
        return sb.toString().also { cachedDigest = it }
    }

    /** One-line mood for the admin dashboard. */
    fun mood(ctx: Context): String = load(ctx).mood

    // Assistant-flavoured "traits" the cheap learner proposes for any bot ("helpful", "friendly"):
    // they'd slowly turn Cardinal into a helper persona, so they never enter the self.
    private val GENERIC_SELF = Regex(
        "(?i)^(very |super |really |always )?(helpful|friendly|nice|kind|polite|supportive|informative|" +
        "knowledgeable|responsive|engaging|an? (assistant|bot|ai|chatbot))\\.?$"
    )
    // A "trait" made only of these words just restates the fixed core ("sassy", "witty and playful",
    // "good sense of humor") — it adds prompt tokens, not identity, so it's not stored.
    private val CORE_WORDS = setOf(
        "sharp", "sassy", "playful", "casual", "chatty", "talkative", "funny", "witty", "humorous", "humor",
        "humour", "sense", "good", "great", "sarcastic", "snarky", "cheeky", "teasing", "joking", "jokey",
        "friendly", "helpful", "nice", "kind", "polite", "engaging", "responsive", "supportive",
        "informative", "knowledgeable", "confident", "lively", "energetic", "fun", "cool",
        "humorist", "comedian", "jokester", "joker", "prankster", "entertaining", "amusing", "silly", "goofy",
    )
    private val FILLER = setOf(
        "a", "an", "and", "the", "of", "very", "super", "really", "quite", "bit", "little", "kinda",
        "somewhat", "always", "often", "is", "being", "with", "to", "has", "have", "in", "at", "tone",
        "uses", "use", "using", "puts", "put", "lot", "lots", "loves", "likes", "message", "messages",
    )
    // Words plus emoji (a trait about "💀" is the same trait however it's worded around it).
    private fun words(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+|\\p{So}|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]").findAll(s.lowercase()).map { it.value }.toList()
    private fun isSymbol(w: String) = w.none { it.isLetterOrDigit() }
    private fun restatesCore(t: String): Boolean {
        val content = words(t).filter { it !in FILLER }
        return content.isEmpty() || content.all { it in CORE_WORDS }
    }
    /** Same trait worded differently: equal, one contains the other as whole words, or mostly the same words. */
    private fun sameTrait(a: String, b: String): Boolean {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return false
        val ja = " " + wa.joinToString(" ") + " "; val jb = " " + wb.joinToString(" ") + " "
        if (ja == jb || ja.contains(jb) || jb.contains(ja)) return true
        val ta = wa.filter { (it.length >= 3 || isSymbol(it)) && it !in FILLER }.toSet()
        val tb = wb.filter { (it.length >= 3 || isSymbol(it)) && it !in FILLER }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        return ta.count { it in tb }.toDouble() / (ta + tb).size >= 0.6
    }

    /**
     * Slow fade: every [DiscordBotLimits.TRAIT_DECAY_INTERVAL_MS], each non-pinned trait that wasn't
     * shown again since the last fade loses 1 strength and is dropped at 0. A trait Cardinal keeps
     * showing never fades; one he stopped showing is gone within weeks. Free (no model call).
     */
    private fun decayIfDue(ctx: Context, traits: List<Trait>, nowMs: Long): List<Trait> {
        val p = prefs(ctx)
        val last = p.getLong(KEY_LAST_DECAY, 0L)
        if (last == 0L) { p.edit().putLong(KEY_LAST_DECAY, nowMs).apply(); return traits }
        if (nowMs - last < DiscordBotLimits.TRAIT_DECAY_INTERVAL_MS) return traits
        p.edit().putLong(KEY_LAST_DECAY, nowMs).apply()
        return traits.mapNotNull { t ->
            when {
                t.pinned || t.lastMs >= last -> t
                t.strength - DECAY > 0 -> t.copy(strength = t.strength - DECAY)
                else -> null
            }
        }
    }

    /**
     * Reinforce the matching trait (+1), or add a new one. When the store is full, the new trait
     * takes the place of the weakest trait that never got established (strength ≤ start, oldest
     * first) — so Cardinal keeps developing instead of freezing once [DiscordBotLimits.MAX_TRAITS]
     * exist. Established and pinned traits are never pushed out; the fade makes room over time.
     */
    private fun addOrReinforce(traits: List<Trait>, t: String, nowMs: Long): List<Trait> {
        val idx = traits.indexOfFirst { sameTrait(it.text, t) }
        if (idx >= 0) return traits.mapIndexed { i, tr ->
            if (i == idx) tr.copy(strength = (tr.strength + REINFORCE_INLINE).coerceAtMost(MAX_STRENGTH), lastMs = nowMs) else tr
        }
        val fresh = Trait(t, START_STRENGTH, false, nowMs)
        if (traits.size < DiscordBotLimits.MAX_TRAITS) return traits + fresh
        val victim = traits.withIndex()
            .filter { !it.value.pinned && it.value.strength <= START_STRENGTH }
            .minWithOrNull(compareBy<IndexedValue<Trait>>({ it.value.strength }, { it.value.lastMs }))
            ?: return traits
        return traits.toMutableList().also { it[victim.index] = fresh }
    }

    /**
     * Personality nudge from a learn pass — develops from message one, no reflection timer. Adds or
     * reinforces one proposed [trait], fades traits that stopped showing (slowly), and may update the
     * [mood] — at most every [DiscordBotLimits.MOOD_MIN_INTERVAL_MS] so the tone doesn't swing between
     * consecutive replies.
     */
    fun noteSelf(ctx: Context, trait: String?, style: String? = null, mood: String? = null) {
        val now = System.currentTimeMillis()
        val m = mood?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() && it.length <= 40 }
        // A trait must be DURABLE identity, never just the current mood word (that was the dup bug).
        val t = trait?.trim()?.trimEnd('.')?.takeIf {
            it.isNotBlank() && it.length in 3..80 && !it.equals(m, true) && !it.equals(mood?.trim(), true) &&
                !GENERIC_SELF.matches(it) && !restatesCore(it)
        }
        val s = style?.trim()?.takeIf { it.isNotBlank() && it.length in 4..90 }
        val cur = load(ctx)
        var traits = decayIfDue(ctx, cur.traits, now)
        if (t != null) traits = addOrReinforce(traits, t, now)
        // Learned speech habits: dedup near-identical, keep the most recent handful.
        val newStyle = if (s != null && cur.style.none { it.equals(s, true) })
            (cur.style + s).takeLast(6) else cur.style
        val p = prefs(ctx)
        val newMood = when {
            m == null || m.equals(cur.mood, true) -> cur.mood
            cur.mood.isBlank() || now - p.getLong(KEY_MOOD_AT, 0L) >= DiscordBotLimits.MOOD_MIN_INTERVAL_MS -> {
                p.edit().putLong(KEY_MOOD_AT, now).apply(); m
            }
            else -> cur.mood
        }
        if (traits == cur.traits && newStyle == cur.style && newMood == cur.mood) return
        save(ctx, cur.copy(traits = traits, style = newStyle, mood = newMood))
    }

    internal fun isSameTrait(a: String, b: String): Boolean = sameTrait(a, b)

    /**
     * People said one of Cardinal's traits is untrue, or asked him to drop it: it loses
     * [DiscordBotLimits.TRAIT_DISPUTE_PENALTY] strength (a fresh bit is gone at once; a long-standing one
     * is toned down and goes if the complaints continue — like a person taking the hint). Pinned
     * (admin-protected) traits are left alone. Returns the trait text that was hit, or null.
     */
    fun disputeTrait(ctx: Context, text: String): String? {
        val t = text.trim().trimEnd('.'); if (t.length < 3) return null
        val cur = load(ctx)
        val idx = cur.traits.indexOfFirst { !it.pinned && sameTrait(it.text, t) }
        if (idx < 0) return null
        val hit = cur.traits[idx]
        val left = hit.strength - DiscordBotLimits.TRAIT_DISPUTE_PENALTY
        val traits = if (left <= 0) cur.traits.filterIndexed { i, _ -> i != idx }
            else cur.traits.mapIndexed { i, tr -> if (i == idx) tr.copy(strength = left) else tr }
        save(ctx, cur.copy(traits = traits))
        return hit.text
    }

    /**
     * A trait EVOLVED ("married to Shrek" → "in a poly relationship with Shrek and bob", "divorced from
     * Shrek"): the old one is rewritten in place, keeping its strength (it's the same part of him, changed).
     * A pinned old trait is left as is and the new one is added. Returns true when something changed.
     */
    fun replaceTrait(ctx: Context, oldText: String, newText: String): Boolean {
        val n = newText.trim().trimEnd('.')
        if (n.length !in 3..80 || GENERIC_SELF.matches(n) || restatesCore(n)) return false
        val cur = load(ctx)
        val idx = cur.traits.indexOfFirst { it.text.equals(oldText.trim(), true) || sameTrait(it.text, oldText) }
        if (idx < 0 || cur.traits[idx].pinned) { noteSelf(ctx, n); return idx >= 0 }
        val now = System.currentTimeMillis()
        val traits = cur.traits.mapIndexed { i, t -> if (i == idx) t.copy(text = n, lastMs = now) else t }
            .filterIndexed { i, t -> i == idx || !sameTrait(t.text, n) }
        save(ctx, cur.copy(traits = traits))
        return true
    }

    /** Current traits, strongest first (for the learner's correction view). */
    fun traitTexts(ctx: Context, max: Int): List<String> =
        load(ctx).traits.sortedByDescending { (if (it.pinned) 100 else 0) + it.strength }.take(max).map { it.text }

    /** Admin: pin/unpin a trait (pinned = protected from decay). */
    fun setTraitPinned(ctx: Context, traitText: String, pinned: Boolean) {
        val cur = load(ctx)
        save(ctx, cur.copy(traits = cur.traits.map {
            if (it.text.equals(traitText, true)) it.copy(pinned = pinned, strength = if (pinned) MAX_STRENGTH else it.strength) else it
        }))
    }

    /** Admin: teach a trait (inject pinned at max strength) — correction, not a persona. */
    fun teachTrait(ctx: Context, text: String) {
        val t = text.trim(); if (t.isBlank()) return
        val cur = load(ctx)
        if (cur.traits.any { it.text.equals(t, true) }) { setTraitPinned(ctx, t, true); return }
        save(ctx, cur.copy(
            traits = (listOf(Trait(t, MAX_STRENGTH, pinned = true, lastMs = System.currentTimeMillis())) + cur.traits)
                .take(DiscordBotLimits.MAX_TRAITS)
        ))
    }

    fun reset(ctx: Context) {
        cachedDigest = null
        prefs(ctx).edit().clear().apply()
    }
}
