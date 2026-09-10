package com.vrca.discordbot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The bot's EVOLVING personality — a small weighted trait store that mutates over time as
 * the periodic reflection loop ([DiscordBotAi.reflect]) watches the server's chat. Traits
 * that keep fitting the room get REINFORCED (strength up); unreinforced ones DECAY and are
 * evicted — so he changes and forgets like a person instead of just accreting. A trait that
 * stays reinforced climbs to MAX_STRENGTH and effectively stops drifting (he "settles"),
 * while weak ones churn. Rendered into the reply prompt as "who you are right now".
 *
 * Plain SharedPreferences (not a secret). The immutable core identity (his name is Cardinal,
 * he's a real regular here) and the reflection's content boundary live in code, not here —
 * this holds only the emergent, mutating traits.
 */
object PersonalityStore {
    private const val PREFS = "vrca_discord_personality"
    private const val KEY_TRAITS = "traits"
    private const val MAX_TRAITS = 14
    private const val START_STRENGTH = 2
    private const val MAX_STRENGTH = 6

    data class Trait(val text: String, val strength: Int)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): List<Trait> {
        val raw = prefs(ctx).getString(KEY_TRAITS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("t").trim()
                if (t.isBlank()) null else Trait(t, o.optInt("s", 1).coerceIn(1, MAX_STRENGTH))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun save(ctx: Context, traits: List<Trait>) {
        val arr = JSONArray()
        traits.forEach { arr.put(JSONObject().put("t", it.text).put("s", it.strength)) }
        prefs(ctx).edit().putString(KEY_TRAITS, arr.toString()).apply()
    }

    /** Rendered into the reply prompt (strongest first). Blank until the bot has evolved. */
    fun snapshot(ctx: Context): String {
        val traits = load(ctx).sortedByDescending { it.strength }
        if (traits.isEmpty()) return ""
        return traits.joinToString("\n") { "- ${it.text}" }
    }

    /**
     * Merge one reflection pass: [proposed] is the model's fresh view of who Cardinal should
     * be now. Still-proposed traits are REINFORCED (+1, capped); dropped ones DECAY (-1) and
     * are evicted at 0; genuinely new ones start at [START_STRENGTH]. Capped to [MAX_TRAITS]
     * by strength so the injected block stays small.
     */
    fun applyReflection(ctx: Context, proposed: List<String>) {
        val current = load(ctx).associateBy { it.text.lowercase() }.toMutableMap()
        val out = ArrayList<Trait>()
        for (p in proposed.map { it.trim() }.filter { it.isNotBlank() }) {
            val k = p.lowercase()
            val existing = current.remove(k)
            val s = ((existing?.strength ?: (START_STRENGTH - 1)) + 1).coerceAtMost(MAX_STRENGTH)
            out.add(Trait(existing?.text ?: p, s))
        }
        for (t in current.values) {                 // not re-proposed → decay
            val s = t.strength - 1
            if (s > 0) out.add(Trait(t.text, s))
        }
        save(ctx, out.sortedByDescending { it.strength }.take(MAX_TRAITS))
    }

    fun reset(ctx: Context) { prefs(ctx).edit().remove(KEY_TRAITS).apply() }
}
