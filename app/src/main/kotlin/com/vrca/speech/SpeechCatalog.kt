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
        /** Offline zipformer transducer (ReazonSpeech Japanese, Vietnamese). */
        TRANSDUCER,
        /** SenseVoice (Chinese, Cantonese, Korean); the language is set explicitly. */
        SENSE_VOICE,
        /** Meta Omnilingual CTC (1,600 languages; used for ones nothing smaller covers). */
        OMNILINGUAL_CTC,
        /** DataoceanAI Dolphin CTC (Asian languages; Thai, and a fast Indonesian tier). */
        DOLPHIN_CTC,
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
        /** Noticeably slower per sentence on a Quest (shown as "slow" in the picker). */
        val slow: Boolean = false,
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
    private const val SV = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
    private const val SV_REV = "2365baeacb507f821a0c8120fcee3d484dba7a07"
    // Third-party mirror of k2-fsa's ReazonSpeech release (no official single-file copy);
    // the files are byte-identical to the official tarball (checked by SHA-256).
    private const val REAZON = "DeL-TaiseiOzaki/sherpa-onnx-zipformer-ja-reazonspeech-2024-08-01"
    private const val REAZON_REV = "13b45961a89ff3633ea9028d7701054268921d50"
    private const val VI = "csukuangfj/sherpa-onnx-zipformer-vi-int8-2025-04-20"
    private const val VI_REV = "b2745a435379992ad3f299635468db0c34918e1e"
    private const val OMNI = "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-1B-ctc-int8-2025-11-12"
    private const val OMNI_REV = "17db76eab583b0b868ffb0df104ab879145087e5"
    private const val DOLPHIN = "csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02"
    private const val DOLPHIN_REV = "c8b6689509acfcd744c04e5e169164f9ac4cae32"
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
        Pack(
            id = "sensevoice",
            title = "SenseVoice (Chinese, Cantonese, Korean)",
            kind = Kind.SENSE_VOICE,
            files = listOf(
                hf(SV, SV_REV, "model.int8.onnx", 239_233_841, "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51"),
                hf(SV, SV_REV, "tokens.txt", 315_894, "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"),
            ),
            ramMb = 340,
            credit = "Alibaba SenseVoice (FunASR model licence)",
        ),
        Pack(
            id = "reazon-ja",
            title = "ReazonSpeech (Japanese)",
            kind = Kind.TRANSDUCER,
            files = listOf(
                hf(REAZON, REAZON_REV, "encoder-epoch-99-avg-1.int8.onnx", 154_670_139, "2c7bd08a8a99f9ddd0d9e458456577b1f6279214e51426f114f9eced44c54e1d"),
                hf(REAZON, REAZON_REV, "decoder-epoch-99-avg-1.onnx", 11_767_836, "58b18211ae06265466bfa17172dab574df94f76c8bcb61a3640c28ba860e4124"),
                hf(REAZON, REAZON_REV, "joiner-epoch-99-avg-1.onnx", 10_720_115, "d38a81d1191c9ed6de6a1719503692e07e3e973e2364adde0abae5eaaded1174"),
                hf(REAZON, REAZON_REV, "tokens.txt", 45_754, "2c3ac659818a48a0c04010e0593bbc4d7c8a24a054340b01131499c05fd52def"),
            ),
            ramMb = 380,
            credit = "ReazonSpeech k2 (Apache-2.0)",
        ),
        Pack(
            id = "zipformer-vi",
            title = "Zipformer (Vietnamese)",
            kind = Kind.TRANSDUCER,
            files = listOf(
                hf(VI, VI_REV, "encoder-epoch-12-avg-8.int8.onnx", 70_876_129, "b3abdef7a660fea7faf5e076b3c7613b0fc98406707103784d018189bb522124"),
                hf(VI, VI_REV, "decoder-epoch-12-avg-8.onnx", 5_165_084, "d1d27cca84c824a8acf5ce6edf0f2c0880cfe295d2e69b95134de1707e1d9998"),
                hf(VI, VI_REV, "joiner-epoch-12-avg-8.int8.onnx", 1_033_417, "38ec49e1c18e4feb0cad4de13e25c83a866cf56f4a66f22e8ff579d591a69a46"),
                hf(VI, VI_REV, "tokens.txt", 25_847, "f536d03c2e95ebd2930cf0abec88e823bd17d3c1933da7ae6a82db3b80605e15"),
            ),
            ramMb = 290,
            credit = "k2-fsa Zipformer Vietnamese (Apache-2.0)",
        ),
        Pack(
            id = "omnilingual-1b",
            title = "Omnilingual 1B (Indonesian, Hindi, Turkish, Filipino)",
            kind = Kind.OMNILINGUAL_CTC,
            files = listOf(
                hf(OMNI, OMNI_REV, "model.int8.onnx", 1_031_628_252, "f7b74c964039162423b83e3fa950ce24810c9a635d9ff8468b5f4d142b7c1e8c"),
                hf(OMNI, OMNI_REV, "tokens.txt", 86_423, "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31"),
            ),
            ramMb = 1700,
            credit = "Meta Omnilingual ASR (Apache-2.0)",
            slow = true,
        ),
        Pack(
            id = "dolphin-small",
            title = "Dolphin small (Thai, Indonesian)",
            kind = Kind.DOLPHIN_CTC,
            files = listOf(
                hf(DOLPHIN, DOLPHIN_REV, "model.int8.onnx", 249_658_954, "c1afcb9265de0ebd853eb8f570b371f399a6f9b2b9af9a3cb17c2e509171e697"),
                hf(DOLPHIN, DOLPHIN_REV, "tokens.txt", 504_662, "c3788261a51df1899ea4b210b552cd42139204de72c0ad60f6cebb199078872e"),
            ),
            ramMb = 600,
            credit = "DataoceanAI Dolphin (Apache-2.0)",
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
        // Parakeet-25 never answered in the wrong language for these (shoot-out check).
        Lang("it", "Italian", "Italiano", listOf(Tier("parakeet-25", "Standard", Quality.GOOD))),
        Lang("bg", "Bulgarian", "Български", listOf(Tier("parakeet-25", "Standard", Quality.GOOD))),
        Lang("pl", "Polish", "Polski", listOf(Tier("parakeet-25", "Standard", Quality.OK))),
        Lang("uk", "Ukrainian", "Українська", listOf(Tier("parakeet-25", "Standard", Quality.OK))),
        Lang("nl", "Dutch", "Nederlands", listOf(Tier("parakeet-25", "Standard", Quality.OK))),
        Lang("zh", "Chinese (Mandarin)", "中文", listOf(Tier("sensevoice", "Standard", Quality.GOOD))),
        Lang("yue", "Cantonese", "粵語", listOf(Tier("sensevoice", "Standard", Quality.GREAT))),
        Lang("ja", "Japanese", "日本語", listOf(Tier("reazon-ja", "Standard", Quality.GREAT))),
        Lang("ko", "Korean", "한국어", listOf(Tier("sensevoice", "Standard", Quality.GREAT))),
        Lang("vi", "Vietnamese", "Tiếng Việt", listOf(Tier("zipformer-vi", "Standard", Quality.GOOD))),
        Lang("th", "Thai", "ไทย", listOf(Tier("dolphin-small", "Standard", Quality.OK))),
        Lang("id", "Indonesian", "Bahasa Indonesia", listOf(
            Tier("omnilingual-1b", "Best", Quality.GOOD),
            Tier("dolphin-small", "Light", Quality.OK),
        )),
        Lang("hi", "Hindi", "हिन्दी", listOf(Tier("omnilingual-1b", "Standard", Quality.GOOD))),
        Lang("tr", "Turkish", "Türkçe", listOf(Tier("omnilingual-1b", "Standard", Quality.OK))),
        Lang("fil", "Filipino", "Filipino", listOf(Tier("omnilingual-1b", "Standard", Quality.OK))),
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
