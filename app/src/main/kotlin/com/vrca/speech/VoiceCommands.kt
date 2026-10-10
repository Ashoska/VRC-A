package com.vrca.speech

/** What a spoken command word does. */
enum class VoiceCommand(val label: String, val what: String) {
    PAUSE("Pause", "Stop typing what you say"),
    RESUME("Resume", "Start typing again"),
    CLEAR("Clear", "Clear the chatbox"),
}

/** Why a heard command word wasn't acted on (shown on the dictation row, so a miss in
 *  VRChat can be checked afterwards: user-reported "sometimes it works, sometimes not"). */
enum class CommandMiss(val why: String) {
    TOO_SOON("too soon after other words"),
    TOO_SHORT("too short: say it slower"),
    TOO_QUIET("quieter than your usual voice"),
    KEPT_TALKING("you kept talking after it"),
}

/**
 * Voice commands: a command word said ON ITS OWN (the whole phrase) pauses, resumes or
 * clears dictation. Several checks keep conversation from firing them (user: "lets think
 * of how to make this not have false triggers"); the engine applies the timing/voice
 * ones, this object the words:
 *  - the whole phrase must be the word (exact, ignoring case/punctuation/spaces);
 *  - ≥ [QUIET_BEFORE_SEC] of quiet before it ([RESUME_QUIET_SEC] for resume while paused:
 *    nothing is being typed then), and no more talking within [CONFIRM_MS] after it
 *    ("Pause! wait…" is conversation, so it goes in as text). Both count only the
 *    WEARER's voice: other players through the speakers used to block real commands;
 *  - clearly voiced ([MIN_VOICED]) and about as loud as you normally talk
 *    ([MIN_LEVEL_RATIO] of the median of your last [LEVEL_WINDOW] phrases, learnt while
 *    paused too): other people and VRChat's sound reach the mic quieter than the wearer,
 *    and the baseline must follow the mic quickly: with VRChat in front sharing it, the
 *    level can differ from the in-app level;
 *  - a command that would change nothing (pause while paused) does nothing.
 * Words are per language (each model hears its own language) and users can replace
 * them, and teach the model their voice ("Teach my voice": hidden spellings behind the word).
 */
object VoiceCommands {
    const val QUIET_BEFORE_SEC = 1.0f
    const val RESUME_QUIET_SEC = 0.5f
    const val CONFIRM_MS = 500L
    const val MIN_VOICED = 4
    const val MIN_LEVEL_RATIO = 0.4f
    const val LEVEL_WINDOW = 8

    private fun cmds(pause: List<String>, resume: List<String>, clear: List<String>) =
        mapOf(VoiceCommand.PAUSE to pause, VoiceCommand.RESUME to resume, VoiceCommand.CLEAR to clear)

    // Defaults per language; variants are spellings the models write for the same word.
    private val DEFAULTS: Map<String, Map<VoiceCommand, List<String>>> = mapOf(
        // Picked on the bench (80 synthetic voices through Parakeet EN): clear 78/80, resume
        // 76, pause 74; "unpause" only 16 (mostly heard "and pause"), so it's gone. A voice the
        // model hears differently (one user's "clear" came out "Claire") is fixed per person
        // by "Teach my voice", not by global sound-alikes (a lone name would fire it).
        "en" to cmds(listOf("pause"), listOf("resume"), listOf("clear")),
        "es" to cmds(listOf("pausa"), listOf("continuar", "reanudar"), listOf("borrar")),
        "pt" to cmds(listOf("pausa", "pausar"), listOf("continuar"), listOf("limpar", "apagar")),
        "fr" to cmds(listOf("pause"), listOf("reprendre"), listOf("effacer")),
        "de" to cmds(listOf("pause"), listOf("weiter"), listOf("löschen")),
        "it" to cmds(listOf("pausa"), listOf("riprendi"), listOf("cancella")),
        "nl" to cmds(listOf("pauze"), listOf("verder"), listOf("wissen")),
        "pl" to cmds(listOf("pauza"), listOf("wznów"), listOf("wyczyść")),
        "uk" to cmds(listOf("пауза"), listOf("продовжити"), listOf("очистити")),
        "ru" to cmds(listOf("пауза"), listOf("продолжить"), listOf("очистить")),
        "bg" to cmds(listOf("пауза"), listOf("продължи"), listOf("изчисти")),
        "tr" to cmds(listOf("duraklat"), listOf("devam"), listOf("temizle")),
        "vi" to cmds(listOf("tạm dừng"), listOf("tiếp tục"), listOf("xóa", "xoá")),
        "th" to cmds(listOf("หยุดชั่วคราว"), listOf("ทำต่อ"), listOf("ล้างข้อความ")),
        "id" to cmds(listOf("jeda"), listOf("lanjut", "lanjutkan"), listOf("hapus")),
        "fil" to cmds(listOf("hinto"), listOf("ituloy", "tuloy"), listOf("burahin")),
        "hi" to cmds(listOf("रुको"), listOf("जारी रखो"), listOf("मिटाओ")),
        "zh" to cmds(listOf("暂停"), listOf("继续"), listOf("清除", "清空")),
        "yue" to cmds(listOf("暫停", "暂停"), listOf("繼續", "继续"), listOf("清除", "清空")),
        "ja" to cmds(listOf("一時停止"), listOf("再開"), listOf("クリア", "消去")),
        "ko" to cmds(listOf("일시정지"), listOf("계속"), listOf("지우기", "삭제")),
    )

    fun defaults(lang: String): Map<VoiceCommand, List<String>> = DEFAULTS[lang] ?: DEFAULTS.getValue("en")

    /** Letters/digits only, lower case: "Pause." = "pause", "un pause" = "unpause". */
    fun norm(text: String): String = text.lowercase().filter { it.isLetterOrDigit() }

    /** How a heard phrase is stored as a word: lower case, edge punctuation trimmed. */
    fun clean(text: String): String = text.lowercase().trim { !it.isLetterOrDigit() }

    /** The command the whole phrase [text] is, or null. */
    fun match(text: String, words: Map<VoiceCommand, List<String>>): VoiceCommand? {
        val n = norm(text)
        if (n.isEmpty()) return null
        return words.entries.firstOrNull { (_, ws) -> ws.any { norm(it) == n } }?.key
    }

    /** Live words so far could still turn out to be a command: hold them back, so a
     *  command never flashes in the chatbox. */
    fun couldBe(text: String, words: Map<VoiceCommand, List<String>>): Boolean {
        val n = norm(text)
        return n.isNotEmpty() && words.values.any { ws -> ws.any { norm(it).startsWith(n) } }
    }

    // Words people say on their own all the time: poor command words.
    private val COMMON = setOf(
        "yes", "no", "yeah", "yep", "okay", "ok", "hi", "hello", "hey", "what", "wait", "stop", "go",
        "so", "right", "sure", "thanks", "thankyou", "bye", "oh", "wow", "nice", "cool", "lol", "sorry",
        "please", "help", "here", "there", "now", "why", "how", "who", "where", "when", "good", "great",
        "fine", "maybe", "true", "really", "huh", "hmm", "mhm", "uh", "um", "yo", "again",
    )

    /** Problems with [word] as the word for [cmd]; empty = fine. */
    fun warnings(word: String, cmd: VoiceCommand, words: Map<VoiceCommand, List<String>>, noSpace: Boolean): List<String> {
        val n = norm(word)
        if (n.isEmpty()) return listOf("Nothing was heard.")
        val out = mutableListOf<String>()
        if (n.length < (if (noSpace) 2 else 3)) out += "Very short, so easy to say by accident."
        if (n in COMMON) out += "A common word you might say on its own in conversation."
        words.entries.firstOrNull { (c, ws) -> c != cmd && ws.any { norm(it) == n } }?.let {
            out += "Already the ${it.key.label} word."
        }
        return out
    }

    /** The word a command shows: the first entry. The rest are spellings the model wrote
     *  for this user's voice ("Teach my voice"), matched but never shown. */
    fun shown(words: List<String>): String = words.firstOrNull().orEmpty()

    /** "Teach my voice": what the model heard that the word [display] doesn't already match. */
    fun learnedSpellings(display: String, heard: List<String>): List<String> =
        learn(heard).first.filter { norm(it) != norm(display) }

    /**
     * "Teach my voice" results: what the model heard each time → the words to keep (most heard
     * first, at most 3) and whether it heard the same thing at least twice (a word heard
     * differently every time won't work reliably).
     */
    fun learn(heard: List<String>): Pair<List<String>, Boolean> {
        val counts = heard.map { clean(it) }.filter { norm(it).isNotEmpty() }
            .groupingBy { norm(it) }.eachCount()
        val firstSpelling = heard.map { clean(it) }.filter { norm(it).isNotEmpty() }.associateBy({ norm(it) }, { it })
        val ranked = counts.entries.sortedByDescending { it.value }.map { firstSpelling.getValue(it.key) }
        return ranked.take(3) to ((counts.values.maxOrNull() ?: 0) >= 2)
    }
}
