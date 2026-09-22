package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cardinal's SELF — a self-grown identity, no admin persona. The only fixed scaffolding is
 * the two-sentence [ANCHOR]; everything else emerges from watching the room and mutates on
 * three timescales:
 *
 *  - **style** (learned, how he talks) — short first-person lines he authored about himself,
 *    so they read as habits, not imposed rules. Replaced wholesale by reflection.
 *  - **traits** (slow, weeks) — weighted likes/dislikes/opinions/running bits; reinforced when
 *    they still fit, decayed and evicted when they don't, so he settles without freezing.
 *  - **mood** (fast, days) — a single slow-moving line for continuity.
 *  - **episodes** (memorable server moments) — a tiny capped list he can reference.
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

    /** The ONLY fixed identity. Deliberately toneless — voice is learned, not decreed. */
    const val ANCHOR = "You're Cardinal. You've been a regular in this Discord for a while."

    private const val START_STRENGTH = 2
    private const val MAX_STRENGTH = 10
    // Growth OUTPACES fade so a trait mentioned even occasionally net-strengthens and
    // "settles"; a genuine trait then survives many quiet windows before it can fade out.
    private const val REINFORCE = 2
    private const val DECAY = 1

    data class Trait(val text: String, val strength: Int, val pinned: Boolean = false)
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
                else Trait(t, o.optInt("s", 1).coerceIn(1, MAX_STRENGTH), o.optBoolean("p", false))
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun save(ctx: Context, self: Self) {
        val traitArr = JSONArray()
        self.traits.forEach {
            traitArr.put(JSONObject().put("t", it.text).put("s", it.strength).put("p", it.pinned))
        }
        prefs(ctx).edit()
            .putString(KEY_STYLE, JSONArray(self.style).toString())
            .putString(KEY_TRAITS, traitArr.toString())
            .putString(KEY_MOOD, self.mood)
            .putString(KEY_EPISODES, JSONArray(self.episodes).toString())
            .apply()
    }

    /** Compact digest injected into the reply prompt (below the anchor). Blank until evolved. */
    fun snapshot(ctx: Context): String {
        val s = load(ctx)
        val sb = StringBuilder()
        if (s.mood.isNotBlank()) sb.append("Mood: ").append(s.mood).append('\n')
        if (s.style.isNotEmpty()) {
            sb.append("How you talk:\n")
            s.style.take(5).forEach { sb.append("- ").append(it).append('\n') }
        }
        val traits = s.traits.sortedByDescending { (if (it.pinned) 100 else 0) + it.strength }
        if (traits.isNotEmpty()) {
            sb.append("You:\n")
            traits.forEach { sb.append("- ").append(it.text).append('\n') }
        }
        if (s.episodes.isNotEmpty()) {
            sb.append("You remember:\n")
            s.episodes.takeLast(3).forEach { sb.append("- ").append(it).append('\n') }
        }
        return sb.toString().trim().take(DiscordBotLimits.SELF_DIGEST_MAX_CHARS)
    }

    /** One-line mood for the admin dashboard. */
    fun mood(ctx: Context): String = load(ctx).mood

    /**
     * Merge one reflection pass (multi-timescale). Reinforce still-proposed traits (+1, capped),
     * decay dropped ones (-1, evict at 0) — but a PINNED trait never decays and never evicts.
     * Style/mood are replaced when provided (fast layers), and one episode may be appended.
     */
    fun applyReflection(
        ctx: Context,
        proposedTraits: List<String>,
        proposedStyle: List<String>? = null,
        proposedMood: String? = null,
        newEpisode: String? = null,
    ) {
        val cur = load(ctx)
        val byKey = cur.traits.associateBy { it.text.lowercase() }.toMutableMap()
        val out = ArrayList<Trait>()
        for (p in proposedTraits.map { it.trim() }.filter { it.isNotBlank() }) {
            val k = p.lowercase()
            val existing = byKey.remove(k)
            // New trait starts at START_STRENGTH; a re-affirmed one grows by REINFORCE.
            val s = if (existing == null) START_STRENGTH
                else (existing.strength + REINFORCE).coerceAtMost(MAX_STRENGTH)
            out.add(Trait(existing?.text ?: p, s, existing?.pinned ?: false))
        }
        // Not re-proposed → fade slowly (never below 1 in a single pass), EXCEPT pinned.
        for (t in byKey.values) {
            if (t.pinned) out.add(t)
            else if (t.strength - DECAY > 0) out.add(Trait(t.text, t.strength - DECAY, false))
        }
        val episodes = if (!newEpisode.isNullOrBlank())
            (cur.episodes + newEpisode.trim()).takeLast(DiscordBotLimits.MAX_EPISODES)
        else cur.episodes
        save(ctx, Self(
            style = (proposedStyle?.map { it.trim() }?.filter { it.isNotBlank() }?.take(6) ?: cur.style),
            traits = out.sortedByDescending { (if (it.pinned) 100 else 0) + it.strength }
                .take(DiscordBotLimits.MAX_TRAITS),
            mood = proposedMood?.trim()?.ifBlank { cur.mood } ?: cur.mood,
            episodes = episodes,
        ))
    }

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
            traits = (listOf(Trait(t, MAX_STRENGTH, pinned = true)) + cur.traits).take(DiscordBotLimits.MAX_TRAITS)
        ))
    }

    fun reset(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
