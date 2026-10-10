package com.vrca.speech

/**
 * Offline voice-to-text catalog: which downloadable model pack serves each language.
 *
 * Shared by every build (only the headset build can actually RUN the packs — see the
 * flavor `SpeechToText`). Each language maps to the pack that measured best for it in
 * the offline engine shoot-out (docs/systems/voice-to-text.md). Packs are plain data:
 * upgrading a language later = pointing it at a different pack, no engine change.
 *
 * Every file is pinned to an exact upstream revision and verified by size + SHA-256
 * after download, so a changed/compromised upstream file can never be loaded.
 */
object SpeechCatalog {

    enum class Quality(val label: String) { GREAT("Great"), GOOD("Good"), OK("OK"), EXPERIMENTAL("Experimental") }

    /** How the engine loads a pack (the sherpa-onnx model family). */
    enum class Kind { NEMO_TRANSDUCER }

    data class PackFile(val name: String, val url: String, val size: Long, val sha256: String)

    data class Pack(
        val id: String,
        val title: String,
        val kind: Kind,
        val files: List<PackFile>,
        /** Approximate RAM while listening (MB), measured on desktop. */
        val ramMb: Int,
        /** Licence/attribution line shown in the language picker. */
        val credit: String,
    ) {
        val sizeBytes: Long get() = files.sumOf { it.size }
        fun file(name: String) = files.first { it.name == name }
    }

    data class Lang(
        val code: String,
        val englishName: String,
        val nativeName: String,
        val packId: String,
        val quality: Quality,
    )

    private fun hf(repo: String, rev: String, name: String, size: Long, sha: String) =
        PackFile(name, "https://huggingface.co/$repo/resolve/$rev/$name", size, sha)

    private const val PK_EN = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
    private const val PK_EN_REV = "1ab9323565ddb038682214b292f588070a538ce2"
    private const val PK_25 = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private const val PK_25_REV = "2bda32ec70b097a55adaa07d9a7173915b43cc78"

    /** Silero voice-activity detector: splits speech into phrases. Shared by all packs. */
    val VAD = Pack(
        id = "vad-silero",
        title = "Voice detector",
        kind = Kind.NEMO_TRANSDUCER, // unused for the VAD
        files = listOf(
            PackFile(
                "silero_vad.onnx",
                "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                643_854, "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
            ),
        ),
        ramMb = 10,
        credit = "Silero VAD (MIT)",
    )

    val packs: Map<String, Pack> = listOf(
        Pack(
            id = "parakeet-en",
            title = "Parakeet (English)",
            kind = Kind.NEMO_TRANSDUCER,
            files = listOf(
                hf(PK_EN, PK_EN_REV, "encoder.int8.onnx", 652_184_296, "a32b12d17bbbc309d0686fbbcc2987b5e9b8333a7da83fa6b089f0a2acd651ab"),
                hf(PK_EN, PK_EN_REV, "decoder.int8.onnx", 7_257_753, "b6bb64963457237b900e496ee9994b59294526439fbcc1fecf705b31a15c6b4e"),
                hf(PK_EN, PK_EN_REV, "joiner.int8.onnx", 1_739_080, "7946164367946e7f9f29a122407c3252b680dbae9a51343eb2488d057c3c43d2"),
                hf(PK_EN, PK_EN_REV, "tokens.txt", 9_384, "ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d"),
            ),
            ramMb = 1000,
            credit = "NVIDIA Parakeet TDT 0.6B v2 (CC-BY-4.0)",
        ),
        Pack(
            id = "parakeet-25",
            title = "Parakeet (European)",
            kind = Kind.NEMO_TRANSDUCER,
            files = listOf(
                hf(PK_25, PK_25_REV, "encoder.int8.onnx", 652_184_281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                hf(PK_25, PK_25_REV, "decoder.int8.onnx", 11_845_275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                hf(PK_25, PK_25_REV, "joiner.int8.onnx", 6_355_277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                hf(PK_25, PK_25_REV, "tokens.txt", 93_939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
            ),
            ramMb = 1000,
            credit = "NVIDIA Parakeet TDT 0.6B v3 (CC-BY-4.0)",
        ),
    ).associateBy { it.id }

    /**
     * Languages offered right now. Parakeet-25 is only used for the languages where it
     * measured as good as the best alternative AND never wrote the wrong language;
     * the other European/Asian languages get their own packs in later phases.
     */
    val languages: List<Lang> = listOf(
        Lang("en", "English", "English", "parakeet-en", Quality.GREAT),
        Lang("es", "Spanish", "Español", "parakeet-25", Quality.GREAT),
        Lang("pt", "Portuguese", "Português", "parakeet-25", Quality.GREAT),
        Lang("ru", "Russian", "Русский", "parakeet-25", Quality.GOOD),
    )

    fun lang(code: String): Lang? = languages.firstOrNull { it.code == code }
    fun pack(id: String): Pack? = if (id == VAD.id) VAD else packs[id]
    fun packForLang(code: String): Pack? = lang(code)?.let { packs[it.packId] }

    /** Best default for this device: the system language if we have it, else English. */
    fun defaultLanguage(): String {
        val sys = java.util.Locale.getDefault().language
        return if (lang(sys) != null) sys else "en"
    }
}
