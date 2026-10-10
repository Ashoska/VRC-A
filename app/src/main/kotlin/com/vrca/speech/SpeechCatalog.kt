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
    enum class Kind {
        /** NeMo transducer, offline (Parakeet). */
        NEMO_TRANSDUCER,
        /** NeMo CTC, offline (GigaAM v3 with punctuation). */
        NEMO_CTC,
        /** NeMo Canary, offline; the language is set explicitly so it can't mix languages up. */
        CANARY,
        /** Streaming zipformer transducer (Kroko), fed one phrase at a time. */
        ONLINE_TRANSDUCER,
    }

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

    /** One size/quality option for a language. Tiers are listed best-first. */
    data class Tier(val packId: String, val label: String, val quality: Quality)

    data class Lang(
        val code: String,
        val englishName: String,
        val nativeName: String,
        val tiers: List<Tier>,
    ) {
        val best: Tier get() = tiers.first()
        fun tier(packId: String?): Tier = tiers.firstOrNull { it.packId == packId } ?: best
    }

    private fun hf(repo: String, rev: String, name: String, size: Long, sha: String) =
        PackFile(name, "https://huggingface.co/$repo/resolve/$rev/$name", size, sha)

    private const val PK_EN = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"
    private const val PK_EN_REV = "1ab9323565ddb038682214b292f588070a538ce2"
    private const val PK_25 = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    private const val PK_25_REV = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    private const val CAN180 = "csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"
    private const val CAN180_REV = "9077164e0d3dd1d5353743e89ceaa1d3a770838c"
    private const val GIGA3 = "csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16"
    private const val GIGA3_REV = "4fb5407ff028a69fec516cdf4c10fac9ddea7c16"
    private const val KROKO = "csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06"
    private const val KROKO_REV = "572aaf4e2e0c603c3fc2a574d096e755a178faa1"

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
        Pack(
            id = "canary-180m",
            title = "Canary 180M (English/Spanish/German/French)",
            kind = Kind.CANARY,
            files = listOf(
                hf(CAN180, CAN180_REV, "encoder.int8.onnx", 132_678_643, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
                hf(CAN180, CAN180_REV, "decoder.int8.onnx", 74_437_848, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
                hf(CAN180, CAN180_REV, "tokens.txt", 53_555, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
            ),
            ramMb = 670,
            credit = "NVIDIA Canary 180M Flash (CC-BY-4.0)",
        ),
        Pack(
            id = "gigaam3-ru",
            title = "GigaAM v3 (Russian)",
            kind = Kind.NEMO_CTC,
            files = listOf(
                hf(GIGA3, GIGA3_REV, "model.int8.onnx", 224_893_661, "d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35"),
                hf(GIGA3, GIGA3_REV, "tokens.txt", 2_007, "142de7570b3de5b3035ce111a89c228e80e6085273731d944093ddf24fa539cd"),
            ),
            ramMb = 320,
            credit = "Sber GigaAM v3 (MIT)",
        ),
        Pack(
            id = "kroko-en",
            title = "Kroko (English, light)",
            kind = Kind.ONLINE_TRANSDUCER,
            files = listOf(
                hf(KROKO, KROKO_REV, "encoder.onnx", 70_092_599, "d4881c57449d581e0770fd53fa66c2fdc6cd167d92ece7c715e603defc96d9d4"),
                hf(KROKO, KROKO_REV, "decoder.onnx", 617_488, "455ba38466fce8d5a57e7db68a323b684079ca4d9e1dd93a740d9b2429aae3b1"),
                hf(KROKO, KROKO_REV, "joiner.onnx", 336_817, "d406f616736350e2a7df3e39398b78eb2fc1a2ca6973a19d3853fa3227e25b52"),
                hf(KROKO, KROKO_REV, "tokens.txt", 6_310, "396dbeb5f4858875690716084f54e90d339679d0ba3e6b5b584f3d7589254d2d"),
            ),
            ramMb = 150,
            credit = "Banafo Kroko (CC-BY-SA-4.0)",
        ),
    ).associateBy { it.id }

    /**
     * Languages offered right now, each with its tiers best-first (bigger + more accurate
     * first, smaller + lighter after) so users can trade accuracy for device space.
     * Quality labels come from the shoot-out (docs/systems/voice-to-text.md). Parakeet-25
     * is only used where it never wrote the wrong language; Canary takes the language
     * explicitly, so it can't confuse them.
     */
    val languages: List<Lang> = listOf(
        Lang("en", "English", "English", listOf(
            Tier("parakeet-en", "Best", Quality.GREAT),
            Tier("canary-180m", "Balanced", Quality.GOOD),
            Tier("kroko-en", "Light", Quality.OK),
        )),
        Lang("es", "Spanish", "Español", listOf(
            Tier("parakeet-25", "Best", Quality.GREAT),
            Tier("canary-180m", "Light", Quality.GREAT),
        )),
        Lang("pt", "Portuguese", "Português", listOf(
            Tier("parakeet-25", "Best", Quality.GREAT),
        )),
        // GigaAM v3 is both the most accurate (6.2% vs Parakeet-25's 10.6%) and the
        // lightest Russian option, so it's the only tier.
        Lang("ru", "Russian", "Русский", listOf(
            Tier("gigaam3-ru", "Standard", Quality.GREAT),
        )),
        Lang("de", "German", "Deutsch", listOf(
            Tier("canary-180m", "Standard", Quality.GREAT),
        )),
        Lang("fr", "French", "Français", listOf(
            Tier("canary-180m", "Standard", Quality.GOOD),
        )),
    )

    fun lang(code: String): Lang? = languages.firstOrNull { it.code == code }
    fun pack(id: String): Pack? = if (id == VAD.id) VAD else packs[id]
    fun packFor(code: String, packId: String?): Pack? = lang(code)?.let { packs[it.tier(packId).packId] }

    /** Best default for this device: the system language if we have it, else English. */
    fun defaultLanguage(): String {
        val sys = java.util.Locale.getDefault().language
        return if (lang(sys) != null) sys else "en"
    }
}
