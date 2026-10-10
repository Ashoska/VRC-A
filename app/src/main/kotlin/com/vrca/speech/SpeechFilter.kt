package com.vrca.speech

/**
 * Drops text a model made up from noise. Fed a rustle, a cough or a long breathy blip,
 * speech models answer with a filler like "okay" or "yeah" (user-reported: "okay" showed
 * up while nobody spoke). Real speech that short still gets through when it has a voice
 * in it; only fillers backed by almost no voiced audio are dropped.
 */
object SpeechFilter {
    /** What models typically "hear" in non-speech. Compared after lower-casing and
     *  stripping punctuation. */
    private val FILLERS = setOf(
        "okay", "ok", "yeah", "yes", "yep", "yup", "mhm", "mm", "mmm", "hmm", "hm", "uh", "um",
        "ah", "oh", "huh", "the", "a", "i", "you", "so", "no", "bye", "thanks", "thank you", "right",
    )

    /** Voiced 32 ms windows (≈ 0.25 s of voice) a lone filler needs to count as said. */
    const val FILLER_MIN_VOICED = 8

    /**
     * Keep a decoded phrase? [voiced] = its voiced 32 ms windows (`Voicing`),
     * [minVoiced] = the sensitivity's bar for "has a voice" (below it the phrase was only
     * decoded because it was long, e.g. whispering).
     */
    fun keep(text: String, voiced: Int, minVoiced: Int, noSpace: Boolean): Boolean {
        val norm = text.lowercase().map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
            .joinToString("").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (norm.isEmpty()) return false
        if (voiced < minVoiced) {
            // Next to no voice: only a real run of words is believable.
            return if (noSpace) norm.sumOf { it.length } >= 4 else norm.size >= 3
        }
        if (!noSpace && voiced < FILLER_MIN_VOICED && norm.joinToString(" ") in FILLERS) return false
        return true
    }
}
