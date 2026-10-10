package com.vrca.speech

/**
 * Joins dictated phrases into chat text. Each phrase is decoded on its own, so the model
 * ends every one with a full stop and starts it with a capital — wrong when the speaker
 * only took a breath mid-sentence ("I think we should. Go to the Black Cat"). So the
 * closing full stop is dropped, a phrase that follows a short pause continues the
 * sentence (first word lower-cased), and a full stop goes back only after a real break.
 */
object PhraseJoin {
    /** Silence (s) that counts as a new sentence; shorter pauses continue the sentence. */
    const val SENTENCE_PAUSE_SEC = 1.5f

    /** [phrase] without the model's closing full stop (? ! and … are kept). */
    fun stripStop(phrase: String): String {
        val p = phrase.trim()
        return if ((p.endsWith('.') && !p.endsWith("..")) || p.endsWith('。')) p.dropLast(1).trimEnd() else p
    }

    /** Languages written without spaces between words: phrases join with no space. */
    private val NO_SPACE = setOf("zh", "yue", "ja")

    /** What goes between two phrases. */
    fun joiner(lang: String): String = if (lang in NO_SPACE) "" else " "

    /** The full stop that closes a sentence after a real break. */
    fun sentenceMark(lang: String): String = if (lang in NO_SPACE) "。" else "."

    /**
     * [text] closed as a sentence after a real break. A comma, semicolon, colon or dash the
     * model left at the end (its phrase was cut mid-sentence) BECOMES the full stop:
     * "you lesbian," → "you lesbian.", not "lesbian,." (user-reported). Already ended:
     * unchanged.
     */
    fun closeSentence(text: String, lang: String): String {
        val t = text.trimEnd()
        if (t.isEmpty() || endsSentence(t)) return t
        val body = t.trimEnd { it in ",;:-–—，、；：" || it.isWhitespace() }
        return if (body.isEmpty()) t else body + sentenceMark(lang)
    }

    /** Whether [text] already ends a sentence. */
    fun endsSentence(text: String): Boolean = text.trimEnd().lastOrNull()?.let { it in ".!?…。！？" } ?: false

    /**
     * [phrase] continuing the previous sentence: a plain capitalised first word goes
     * lower-case ("Go" → "go"). Kept: "I"/"I'm"…, acronyms ("OK", "USA"), and German,
     * where every noun is capitalised so the capital may be real.
     */
    fun continueCase(phrase: String, lang: String): String {
        if (lang == "de") return phrase
        val word = phrase.substringBefore(' ')
        if (word.length < 2 || !word[0].isUpperCase() || !word[1].isLowerCase()) return phrase
        if (word == "I" || word.startsWith("I'") || word.startsWith("I’")) return phrase
        return word[0].lowercaseChar() + phrase.substring(1)
    }
}
