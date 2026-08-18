package com.vrca.nowplaying

import android.content.Context
import org.json.JSONObject

/**
 * EXACT chatbox line-width calibration (plan §8.4) — replaces [TitleCleaner]'s
 * ESTIMATED per-glyph widths + the `VISUAL_LINE_UNITS = 28.5` fudge with values
 * MEASURED in-headset.
 *
 * Method (the harness in Settings → Debug drives it): send a line of a single
 * repeated character and note the max count that stays on ONE line before it
 * wraps. That count is stored per character; each copy of char `c` then consumes
 * `1 / maxCount(c)` of the line, so a string fits on one line when the fractions
 * sum to ≤ 1.0. Unmeasured chars fall back to TitleCleaner's estimate, SCALED
 * into this fraction system (see `TitleCleaner.charWidth`).
 *
 * In-memory (loaded once via [attach]) so [TitleCleaner] stays a pure object with
 * no Context. `generation` bumps on every save for cache invalidation.
 */
object ChatboxCalibration {

    private const val PREFS = "vrca_chatbox_calib"
    private const val KEY = "max_counts" // JSON {"<char>": maxCount}

    /** Reference chars spanning the width spectrum — what the harness measures. */
    val REFERENCE_CHARS: List<Char> = listOf(
        'i', 'l', '.', '\'', '|',            // narrowest
        'I', 'j', 'f', 't', 'r', ' ',        // narrow
        'n', 'o', 'a', 's', 'e', 'c', '0',   // medium
        'm', 'w',                            // wide
        'M', 'W', '@',                       // widest Latin
        'Ｗ'                                 // fullwidth (2× anchor)
    )

    @Volatile private var maxCounts: Map<Char, Int> = emptyMap()
    @Volatile var generation: Int = 0
        private set

    val calibrated: Boolean get() = maxCounts.isNotEmpty()

    /** Measured fraction of one line for `c`, or null if not measured. */
    fun measuredUnit(c: Char): Float? = maxCounts[c]?.takeIf { it > 0 }?.let { 1f / it }

    fun measuredChars(): Map<Char, Int> = maxCounts

    fun attach(context: Context) {
        val raw = prefs(context).getString(KEY, null) ?: return
        loadFrom(raw)
    }

    fun save(context: Context, counts: Map<Char, Int>) {
        val clean = counts.filterValues { it > 0 }
        val json = JSONObject().apply { clean.forEach { (c, n) -> put(c.toString(), n) } }.toString()
        prefs(context).edit().putString(KEY, json).apply()
        loadFrom(json)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
        maxCounts = emptyMap()
        generation++
    }

    private fun loadFrom(json: String) {
        maxCounts = try {
            val obj = JSONObject(json)
            buildMap {
                obj.keys().forEach { k -> if (k.isNotEmpty()) obj.optInt(k, 0).let { if (it > 0) put(k[0], it) } }
            }
        } catch (e: Exception) {
            emptyMap()
        }
        generation++
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
