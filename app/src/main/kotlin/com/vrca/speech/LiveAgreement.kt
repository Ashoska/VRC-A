package com.vrca.speech

/**
 * Live words for phrase models without re-reading the whole sentence every time ("local
 * agreement", the trick whisper-streaming uses): words that two re-reads in a row agree on
 * are locked in and never re-read; the next re-read starts just before the first word that
 * is still open. Each re-read then covers ~2–3 s however long you talk, instead of the
 * whole sentence so far (bench, FLEURS: Parakeet 2.2 s vs 5.0 s, GigaAM 2.6 vs 5.6,
 * SenseVoice 2.7 vs 7.0), and the live text stays within ~2–6% of the final read.
 *
 * Needs token timestamps (Canary has none: the caller re-reads whole sentences there).
 * One instance per phrase; the final read of the whole phrase replaces all of it anyway.
 */
class LiveAgreement(private val noSpace: Boolean) {
    /** A word and when it starts, in seconds from the phrase start. */
    class Word(val text: String, val start: Double)

    private val locked = ArrayList<Word>()
    private var open: List<Word>? = null

    /** Where the next re-read should start, in seconds from the phrase start. */
    var cutSec = 0.0
        private set

    /**
     * One re-read of the phrase audio from [chunkStartSec] to now: its [tokens] and their
     * [times] (seconds, relative to the chunk). Returns the text to show.
     */
    fun update(tokens: Array<String>, times: FloatArray, chunkStartSec: Double): String {
        val hyp = words(tokens, times, chunkStartSec).toMutableList()
        // The overlap before the cut can repeat the last locked word: drop that copy.
        while (locked.isNotEmpty() && hyp.isNotEmpty() &&
            norm(hyp[0].text) == norm(locked.last().text) && hyp[0].start < chunkStartSec + 0.4
        ) hyp.removeAt(0)
        var k = 0
        open?.let { prev ->
            while (k < minOf(prev.size, hyp.size) && norm(prev[k].text) == norm(hyp[k].text)) k++
            k = minOf(k, hyp.size - 1) // always keep the newest word open: it may still change
        }
        if (k > 0) {
            locked.addAll(hyp.subList(0, k))
            cutSec = maxOf(cutSec, hyp[k].start - 0.15)
            open = hyp.subList(k, hyp.size).toList()
        } else {
            open = hyp
        }
        return text()
    }

    fun text(): String = (locked + (open ?: emptyList())).joinToString(if (noSpace) "" else " ") { it.text }

    private fun words(tokens: Array<String>, times: FloatArray, offset: Double): List<Word> {
        val out = ArrayList<Pair<StringBuilder, Double>>()
        var space = false
        for (i in tokens.indices) {
            val tok = tokens[i]
            val t = tok.replace('▁', ' ').trim()
            if (t.isEmpty()) { space = true; continue } // a bare "▁" token = word boundary (GigaAM)
            val at = offset + (times.getOrNull(i) ?: 0f)
            val punctuation = t.all { !it.isLetterOrDigit() }
            val startsWord = space || tok.startsWith('▁') || tok.startsWith(' ') || out.isEmpty() ||
                t.any { isCjk(it) } || out.last().first.lastOrNull()?.let { isCjk(it) } == true
            space = false
            if (startsWord && !punctuation) out += StringBuilder(t) to at
            else if (out.isNotEmpty()) out.last().first.append(t)
            else out += StringBuilder(t) to at
        }
        return out.map { Word(it.first.toString(), it.second) }
    }

    private fun isCjk(c: Char): Boolean =
        c in '　'..'鿿' || c in '가'..'힯' || c in '＀'..'￯'

    private fun norm(w: String): String = w.lowercase().filter { it.isLetterOrDigit() }
}
