package com.vrca.ui.viewmodel

/**
 * Live + Scroll chatbox layout (pure text logic, shared by typing and dictation).
 *
 * VRChat wraps a long chatbox message into several visual lines, so we wrap it
 * OURSELVES (by estimated proportional width) and keep only the newest [LINES]
 * lines — older lines scroll off the top like captions. Splitting only on '\n'
 * treated one long unbroken message as a single line and trimmed CHARACTERS off
 * the front instead of dropping whole lines (the reported bug this replaced).
 */
internal object ChatboxScroll {
    /** Lines kept visible; older ones scroll off. */
    const val LINES = 4
    /** Estimated width each line wraps at: a touch narrower than VRChat's ~30-unit
     *  chatbox wrap so VRChat never re-wraps our lines into a 5th. */
    const val WIDTH = 26f

    /** A line may end EARLY at a natural pause once it's this full: after a sentence
     *  end (. ! ?) at 40%, after a clause mark (, ; :) at 65%. Lines then read as whole
     *  thoughts; plain greedy wrapping chopped sentences mid-way ("sorry I was / gone
     *  for a bit. My cat / knocked my water over"), which read badly in a 4-line window. */
    private const val SENTENCE_BREAK_FILL = 0.40f
    private const val CLAUSE_BREAK_FILL = 0.65f

    /**
     * Newest [LINES] wrapped lines of [text] joined with '\n' (locking the breaks so
     * VRChat shows exactly these lines), capped at [budget] chars so nothing is lost
     * at a wrap point ("drop the 4th line" backstop).
     */
    fun format(text: String, budget: Int): String {
        if (text.isEmpty()) return ""
        val lines = wrap(text, WIDTH)
        val kept = ArrayDeque<String>()
        var total = 0
        // Walk from the newest wrapped line backwards, keeping what fits.
        for (i in lines.indices.reversed()) {
            if (kept.size >= LINES) break
            val line = lines[i]
            val add = line.length + if (kept.isEmpty()) 0 else 1 // +1 for the join '\n'
            if (total + add > budget) {
                if (kept.isEmpty()) return line.takeLast(budget) // newest line alone over budget
                break
            }
            kept.addFirst(line)
            total += add
        }
        return kept.joinToString("\n")
    }

    /** Estimated proportional glyph width (VRChat's chatbox is centered
     *  proportional text, so caps/wide glyphs wrap sooner than a char count). */
    fun charWidth(c: Char): Float = when {
        c == ' ' -> 0.5f
        isWide(c) -> 2.0f // CJK + full-width forms: about two Latin letters wide
        c in "iIlj|.,:;'!`" -> 0.5f
        c in "mwMW" -> 1.5f
        c.isUpperCase() || c.isDigit() -> 1.15f
        else -> 1.0f
    }

    /** Chinese/Japanese/Korean characters and full-width punctuation. */
    private fun isWide(c: Char): Boolean {
        val code = c.code
        if (code in 0x3000..0x303F || code in 0xFF00..0xFFEF) return true
        return when (Character.UnicodeScript.of(code)) {
            Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
            Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL -> true
            else -> false
        }
    }

    fun strWidth(s: String): Float {
        var t = 0f; for (c in s) t += charWidth(c); return t
    }

    /** Greedy word-wrap into visual lines of ≤ [maxW] estimated width, honoring
     *  explicit newlines and hard-splitting a single word longer than a line. */
    fun wrap(text: String, maxW: Float): List<String> {
        val out = mutableListOf<String>()
        for (para in text.split("\n")) {
            val cur = StringBuilder()
            var curW = 0f
            for (word in para.split(" ")) {
                var wd = word
                // Hard-split a word that alone exceeds a full line.
                while (strWidth(wd) > maxW) {
                    if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0); curW = 0f }
                    val sb = StringBuilder(); var acc = 0f; var i = 0
                    while (i < wd.length) {
                        val cw = charWidth(wd[i])
                        if (acc + cw > maxW && sb.isNotEmpty()) break
                        sb.append(wd[i]); acc += cw; i++
                    }
                    out.add(sb.toString())
                    wd = wd.substring(i.coerceAtLeast(1))
                }
                val sepW = if (cur.isEmpty()) 0f else charWidth(' ')
                val wW = strWidth(wd)
                // An empty word (the space just typed after "bit.") never breaks: the
                // line only moves on once the next word starts, so typing doesn't jump.
                val pauseBreak = cur.isNotEmpty() && wd.isNotEmpty() && endsAtPause(cur, curW, maxW)
                if (cur.isNotEmpty() && (curW + sepW + wW > maxW || pauseBreak)) {
                    out.add(cur.toString()); cur.setLength(0); curW = 0f
                    cur.append(wd); curW = wW
                } else {
                    if (cur.isNotEmpty()) { cur.append(' '); curW += sepW }
                    cur.append(wd); curW += wW
                }
            }
            out.add(cur.toString())
        }
        return out
    }

    /** True when [line] ends at a sentence/clause mark (closing quotes or brackets
     *  ignored) and is full enough that ending it there reads naturally. */
    private fun endsAtPause(line: CharSequence, w: Float, maxW: Float): Boolean {
        var i = line.length - 1
        while (i >= 0 && line[i] in "\"')]»”’") i--
        if (i < 0) return false
        return when (line[i]) {
            '.', '!', '?', '…', '。', '！', '？' -> w >= maxW * SENTENCE_BREAK_FILL
            ',', ';', ':', '，', '、', '；', '：' -> w >= maxW * CLAUSE_BREAK_FILL
            else -> false
        }
    }

    /**
     * Next piece of a dictated [phrase] to add after the chatbox's [shown] text, and the
     * rest. The whole phrase when adding it scrolls nothing off; otherwise one line's
     * worth: words are taken until the bottom line is full (or ends at a natural pause),
     * so the window scrolls by exactly one complete line ([CaptionPacer] times each one).
     */
    fun nextPiece(shown: String, phrase: String): Pair<String, String> {
        val p = phrase.trim()
        if (scrolledOff(shown, p) == 0) return p to ""
        val base = flow(shown)
        val baseLines = lineCount(base)
        // Text without spaces (Chinese, Japanese) goes character by character.
        val sep = if (p.contains(' ')) " " else ""
        val words = if (sep.isEmpty()) p.map { it.toString() } else p.split(" ").filter { it.isNotEmpty() }
        var n = 1
        while (n < words.size && wrap(join(base, words.take(n + 1).joinToString(sep)), WIDTH).size <= baseLines + 1) n++
        return words.take(n).joinToString(sep) to words.drop(n).joinToString(sep)
    }

    /** Wrapped chatbox lines in [shown]. */
    fun lineCount(shown: String): Int = flow(shown).let { if (it.isEmpty()) 0 else wrap(it, WIDTH).size }

    /** New lines that adding [piece] after [shown] starts. */
    fun linesAdded(shown: String, piece: String): Int =
        lineCount(join(flow(shown), piece.trim())) - lineCount(shown)

    /** Lines that adding [piece] after [shown] pushes off the top of the window. */
    fun scrolledOff(shown: String, piece: String): Int =
        (lineCount(shown) + linesAdded(shown, piece) - LINES).coerceAtLeast(0)

    /** Whether [piece]'s first word lands on [shown]'s (unfinished) bottom line. */
    fun extendsBottomLine(shown: String, piece: String): Boolean {
        val first = piece.trim().substringBefore(' ')
        return first.isNotEmpty() && lineCount(shown) > 0 && linesAdded(shown, first) == 0
    }

    private fun flow(shown: String) = shown.replace("\n", " ").trim()
    private fun join(a: String, b: String) = if (a.isEmpty()) b else if (b.isEmpty()) a else "$a $b"
}

/**
 * Times dictated text into the 4-line chatbox the way roll-up live captions do: text
 * that scrolls nothing off goes in at once; otherwise it rolls up ONE line at a time
 * ([ChatboxScroll.nextPiece]), at most one line per [scrollGapMs], and never before the
 * line leaving has been readable for [minLineMs]. Normal speech (~2 s per line) never
 * waits; a burst (a long sentence decoded at once) rolls in smoothly instead of jumping
 * several lines (or scrolling past unread, as whole-phrase appends did).
 */
internal class CaptionPacer(
    private val minLineMs: Long = 6_000L,
    private val scrollGapMs: Long = 1_500L,
) {
    /** When each visible line last got words, top line first. */
    private val shownAt = ArrayDeque<Long>()
    private var lastScrollAt = Long.MIN_VALUE / 2

    /** Lines added outside dictation (typing, a cleared field) count as already read. */
    private fun sync(shown: String) {
        val visible = ChatboxScroll.lineCount(shown)
        while (shownAt.size > visible) shownAt.removeFirst()
        while (shownAt.size < visible) shownAt.addFirst(0L)
    }

    /** What to add next after [shown], the rest of [phrase], and how long to wait first. */
    fun next(shown: String, phrase: String, now: Long): Triple<String, String, Long> {
        sync(shown)
        val (piece, rest) = ChatboxScroll.nextPiece(shown, phrase)
        val off = ChatboxScroll.scrolledOff(shown, piece)
        val wait = if (off == 0) 0L else maxOf(
            shownAt.elementAt(off - 1) + minLineMs - now, // the leaving line was readable long enough
            lastScrollAt + scrollGapMs - now,              // one line per gap: a smooth roll-up
            0L,
        )
        return Triple(piece, rest, wait)
    }

    /** Record that [piece] went in after [shown] at [now]. */
    fun added(shown: String, piece: String, now: Long) {
        sync(shown)
        val extends = ChatboxScroll.extendsBottomLine(shown, piece)
        val off = ChatboxScroll.scrolledOff(shown, piece)
        val add = ChatboxScroll.linesAdded(shown, piece)
        if (extends && shownAt.isNotEmpty()) shownAt[shownAt.lastIndex] = now
        if (off > 0) lastScrollAt = now
        repeat(off) { shownAt.removeFirstOrNull() }
        repeat(add) { shownAt.addLast(now) }
    }
}
