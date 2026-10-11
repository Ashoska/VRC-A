package com.vrca.speech

/**
 * VRChat's own words, spelled the way the community writes them. Speech models learnt
 * everyday text, so they split these (user-reported: "VR Chat", "Chat Box"). Measured on
 * the bench (30 synthetic voices each, Parakeet EN): "V-R-Chat" came out as "VR chat" most
 * of the time, "chatbox" as "chat box" 23/30. Applied to everything dictated (live words
 * and finals); only Latin-letter patterns, so other scripts pass through untouched.
 * Deliberately not corrected: real words a mishearing can produce ("checkbox").
 */
object SpeechVocab {
    private val RULES: List<Pair<Regex, String>> = listOf(
        // "VR chat", "V R chat", "V.R. Chat", "vr-chat", "VRchat" → VRChat (never "VR" alone).
        Regex("""\b[Vv]\.?\s?[Rr]\.?[\s-]?[Cc]hat\b""") to "VRChat",
        // "V R C", "V. R. C." → VRC (spelled-out letters only; "VRC" is already right).
        Regex("""\b[Vv]\.?\s[Rr]\.?\s[Cc]\b\.?""") to "VRC",
        // "chat box", "Chat Box", "chat-box(es)" → chatbox, keeping a sentence's capital.
        Regex("""\b([Cc])hat[\s-]+[Bb]ox(es)?\b""") to "$1hatbox$2",
    )

    fun fix(text: String): String = RULES.fold(text) { t, (re, to) -> re.replace(t, to) }
}
