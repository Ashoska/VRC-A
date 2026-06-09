package com.vrca.nowplaying

/**
 * Fits a now-playing "artist — title" onto a SINGLE chatbox line without rendering an
 * unidentifiable stub. YouTube / YouTube Music (and sometimes Spotify) titles are long
 * and padded with non-identifying cruft ("(Official Music Video)", "- Topic", "| Label",
 * "(Remastered 2021)", trailing #shorts, emoji …). A blunt `take(N)` cut chops mid-word
 * and can destroy the song's identity, so we escalate cheapest-loss → most-loss and stop
 * the moment it fits:
 *
 *   1. strip non-identifying cruft (Tier 1) — FREE, no identity loss
 *   2. de-duplicate the artist embedded in the title ("Artist - Song")
 *   3. (if still over) drop the borderline Tier-2 tags (feat./deluxe/radio edit …)
 *   4. (if still over) drop the separate artist — the song name identifies it
 *   5. (last resort) word-boundary truncate the title with "…", preferring a clean cut
 *
 * Tier-3 keep-words (Remix / Live / Acoustic / Instrumental / Taylor's Version …) are an
 * identity-bearing variant and are NEVER stripped — a bracket group or " - " suffix that
 * contains a keep-word is left intact even if it also contains a cruft word.
 */
object TitleCleaner {

    // Tier 3 — these denote a DIFFERENT recording/track; never strip a group containing one.
    private val keep = Regex(
        "(?i)\\b(" +
            "remix|vip|bootleg|flip|mashup|rework|" +
            "acoustic|live|unplugged|instrumental|a\\s?cappella|acapella|" +
            "demo|cover|reprise|interlude|" +
            "sped\\s?up|speed\\s?up|slowed|reverb|nightcore|8d|" +
            "taylor'?s\\s+version|[a-z]+'s\\s+version|" +
            "(japanese|korean|english|spanish|french|mandarin|cantonese|chinese|german|portuguese|italian)\\s+(ver|version)|" +
            "from\\s+[\"“]|soundtrack|original\\s+motion\\s+picture" +
            ")\\b"
    )

    // Tier 1 — always strip a bracket group / trailing " - "|"| " segment that contains these.
    private val tier1 = Regex(
        "(?i)\\b(" +
            "official\\s*(music\\s*)?video|official\\s*audio|official\\s*lyrics?\\s*video|" +
            "official\\s*visuali[sz]er|official\\s*video|" +
            "music\\s*video|m\\s*/\\s*v|lyrics?\\s*video|with\\s+lyrics|lyrics?|visuali[sz]er|" +
            "performance\\s*video|vertical\\s*video|dance\\s*practice|choreography|color\\s*coded|" +
            "audio|video|hd|hq|fhd|uhd|4k|8k|1080p|720p|high\\s*quality|" +
            "remaster(ed)?(\\s*\\d{4})?|\\d{4}\\s*remaster|digitally\\s*remastered|mono|stereo|" +
            "out\\s+now|available\\s+now|stream\\s+now|listen\\s+now|free\\s+(download|dl)|" +
            "ncs(\\s+release)?|monstercat|proximity|provided\\s+to\\s+youtube|topic" +
            ")\\b"
    )

    // Tier 2 — drop only when cleanup so far still didn't fit on one line.
    private val tier2 = Regex(
        "(?i)\\b(" +
            "feat\\.?|ft\\.?|featuring|w/|" +
            "deluxe(\\s+edition)?|anniversary\\s+edition|bonus\\s+track|" +
            "radio\\s+edit|album\\s+version|single\\s+version|original\\s+(mix|version)|" +
            "extended(\\s+(mix|version))?|explicit|clean(\\s+version)?" +
            ")\\b"
    )

    private val bracketGroup = Regex("[\\(\\[\\{]([^\\(\\)\\[\\]\\{\\}]*)[\\)\\]\\}]")
    private val emojiBmp = Regex("[\\p{So}\\p{Cn}\\uFE0F\\u2600-\\u27BF\\u2B00-\\u2BFF]")
    private val emojiSurrogate = Regex("[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]")
    private val trailingHashtags = Regex("(?i)(\\s+#\\w+)+\\s*$")
    private val multiSpace = Regex("\\s{2,}")

    /** Tier-1 cleanup: free, no identity loss. */
    fun clean(raw: String): String = strip(raw, aggressive = false)

    /** Tier-1 + Tier-2 cleanup: drops borderline tags too (feat./deluxe/…). */
    fun cleanAggressive(raw: String): String = strip(raw, aggressive = true)

    private fun strip(raw: String, aggressive: Boolean): String {
        if (raw.isBlank()) return ""
        var s = raw

        // Remove emoji / pictographs / variation selectors.
        s = emojiSurrogate.replace(s, "")
        s = emojiBmp.replace(s, "")

        // Strip cruft bracket groups (keep-words win — leave the whole group intact).
        s = bracketGroup.replace(s) { m ->
            val inner = m.groupValues[1]
            when {
                keep.containsMatchIn(inner) -> m.value
                tier1.containsMatchIn(inner) -> ""
                aggressive && tier2.containsMatchIn(inner) -> ""
                else -> m.value
            }
        }

        // Drop trailing "| ..." promo/label segment (unless it carries a keep-word).
        run {
            val idx = s.lastIndexOf('|')
            if (idx > 0) {
                val tail = s.substring(idx + 1)
                if (!keep.containsMatchIn(tail)) s = s.substring(0, idx)
            }
        }

        // Drop trailing " - <tag>" version/promo suffixes (Spotify's convention), one or
        // more, as long as the trailing segment is cruft and not a keep-word.
        while (true) {
            val idx = s.lastIndexOf(" - ")
            if (idx <= 0) break
            val tail = s.substring(idx + 3)
            val isCruft = !keep.containsMatchIn(tail) &&
                (tier1.containsMatchIn(tail) || (aggressive && tier2.containsMatchIn(tail)))
            if (isCruft) s = s.substring(0, idx) else break
        }

        s = trailingHashtags.replace(s, "")
        return tidy(s)
    }

    /** Collapse whitespace and strip empty brackets / dangling separators. */
    private fun tidy(input: String): String {
        var s = input
        s = Regex("[\\(\\[\\{]\\s*[\\)\\]\\}]").replace(s, "") // empty brackets
        s = multiSpace.replace(s, " ")
        s = s.trim().trim('-', '|', '·', '–', '—', '•', ' ').trim()
        return s
    }

    /** If the title begins with "Artist - ", drop that prefix (we show artist separately). */
    private fun dedupArtist(title: String, artist: String): String {
        if (artist.isBlank()) return title
        val a = artist.trim()
        for (sep in listOf(" - ", " – ", " — ", " | ", " : ")) {
            val prefix = a + sep
            if (title.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                return title.substring(prefix.length).trim()
            }
        }
        return title
    }

    /**
     * Fit "artist — title" onto one line, escalating only as far as needed. Returns the
     * single-line string (already trimmed). "Fits" is a DUAL constraint: ≤ [maxLine]
     * chars (the chatbox char budget) AND ≤ [VISUAL_LINE_UNITS] estimated visual width.
     * VRChat's chatbox wraps centered PROPORTIONAL text at ~30 average-character widths
     * — a 41-char title within the 42-char budget still wrapped to a second rendered
     * line when caps-heavy ("Theodore Roosevelt Would LOVE…" wrapped at ~29 chars,
     * "…the DUMBEST mfs on earth" at ~31). The char cap stays at 42 so a skinny
     * all-lowercase title can still use every char that genuinely fits on one line.
     */
    fun fitOneLine(rawTitle: String, rawArtist: String, maxLine: Int): String {
        val artist = clean(rawArtist).trim()
        val title1 = dedupArtist(clean(rawTitle), artist)

        fun combined(t: String): String =
            if (artist.isNotBlank() && t.isNotBlank()) "$artist — $t" else t

        // 1. cleaned artist + title
        combined(title1).let { if (fitsOneLine(it, maxLine)) return it }

        // 2. drop Tier-2 tags from the title, keep artist
        val titleAgg = dedupArtist(cleanAggressive(rawTitle), artist)
        combined(titleAgg).let { if (fitsOneLine(it, maxLine)) return it }

        // 3. drop the artist, cleaned title
        if (fitsOneLine(title1, maxLine)) return title1
        // 4. drop the artist, aggressive title
        if (fitsOneLine(titleAgg, maxLine)) return titleAgg

        // 5. last resort — word-boundary truncate the most-cleaned title.
        return truncate(titleAgg.ifBlank { title1 }, maxLine)
    }

    private fun fitsOneLine(s: String, maxLine: Int): Boolean =
        s.length <= maxLine && visualWidth(s) <= VISUAL_LINE_UNITS

    /**
     * Crude proportional-width estimate in "average character" units, tuned against
     * observed VRChat chatbox wraps. Not real font metrics — just enough to catch
     * caps-heavy strings that blow past the box while letting narrow text run longer.
     */
    private fun visualWidth(s: String): Float {
        var w = 0f
        for (c in s) w += charWidth(c)
        return w
    }

    private fun charWidth(c: Char): Float = when {
        // The ellipsis is three dots on ONE char — ~2 average chars wide. Counting
        // it as 1.0 made truncate() emit a "shortened to fit" title that STILL
        // wrapped: the appended … itself pushed the line over ("Theodore Roosevelt
        // Would LOVE…" wrapped the whole LOVE… token in-game).
        c == '…' -> 2.0f
        c == 'm' || c == 'w' || c == 'M' || c == 'W' -> 1.4f
        c.isUpperCase() || c.isDigit() -> 1.2f
        c in NARROW_CHARS -> 0.6f
        // CJK / fullwidth glyphs render roughly double an average Latin char.
        c.code in 0x2E80..0x9FFF || c.code in 0xAC00..0xD7AF ||
            c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFF60 ||
            c.code in 0x3000..0x303F -> 2.0f
        else -> 1.0f
    }

    private const val NARROW_CHARS = " ijltfr.,':;!|()[]-"

    /**
     * Truncate with an ellipsis to the dual budget (≤ maxLine-1 chars AND inside
     * [VISUAL_LINE_UNITS] visually), preferring a whole-word boundary — but only if
     * cutting at that boundary doesn't waste too much of the line (≤ WORD_SLACK chars);
     * otherwise hard-cut so we keep as much identifying text as possible.
     */
    private fun truncate(text: String, maxLine: Int): String {
        if (fitsOneLine(text, maxLine)) return text
        // Largest prefix whose width + ellipsis stays inside the visual budget.
        var w = charWidth('…')
        var n = 0
        val cap = (maxLine - 1).coerceAtLeast(1).coerceAtMost(text.length)
        while (n < cap) {
            val cw = charWidth(text[n])
            if (w + cw > VISUAL_LINE_UNITS) break
            w += cw
            n++
        }
        val budget = n.coerceAtLeast(1)
        val space = text.lastIndexOf(' ', budget)
        val base = if (space >= budget - WORD_SLACK && space > 0) text.substring(0, space)
        else text.take(budget)
        return base.trimEnd() + "…"
    }

    private const val WORD_SLACK = 12

    // One VRChat chatbox line ≈ 30-31.5 average-character units (empirical: a 27.8-unit
    // prefix fit while 31.6 wrapped; 30.0 fit while 34.8 wrapped). 28.5 buys safety
    // margin for estimator noise — a third in-game sample wrapped right at the old
    // 30-unit edge once the ellipsis was counted honestly.
    private const val VISUAL_LINE_UNITS = 28.5f
}
