package com.vrca.speech

import kotlin.math.roundToInt

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

    enum class Quality(val label: String) { EXCELLENT("Excellent"), GREAT("Great"), GOOD("Good"), OK("OK"), EXPERIMENTAL("Experimental") }

    /** How the engine loads a pack (the sherpa-onnx model family). */
    enum class Kind {
        /** NeMo transducer, offline (Parakeet). */
        NEMO_TRANSDUCER,
        /** NeMo CTC, offline (GigaAM v3 with punctuation). */
        NEMO_CTC,
        /** NeMo Canary, offline; the language is set explicitly so it can't mix languages up. */
        CANARY,
        /** Streaming zipformer transducer (Kroko, small Russian), fed one phrase at a time. */
        ONLINE_TRANSDUCER,
        /** Streaming zipformer CTC (small Chinese), fed one phrase at a time. */
        ONLINE_CTC,
        /** Offline zipformer transducer (ReazonSpeech Japanese, Vietnamese). */
        TRANSDUCER,
        /** SenseVoice (Chinese, Cantonese, Korean); the language is set explicitly. */
        SENSE_VOICE,
        /** Meta Omnilingual CTC (1,600 languages; used for ones nothing smaller covers). */
        OMNILINGUAL_CTC,
        /** DataoceanAI Dolphin CTC (light tiers for several Asian languages). */
        DOLPHIN_CTC,
        /** OpenAI Whisper (language set explicitly; always encodes a 30 s window, so slower). */
        WHISPER,
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

    /**
     * One size/quality option for a language, listed best-first. [errorPct] = % of words
     * (characters for zh/yue/ja/ko/th) wrong on the shoot-out's FLEURS read-speech test
     * (tools/speech-bench) — the SAME yardstick for every language, so the shown accuracy
     * compares across tiers. The quality label is derived from it, never set by hand.
     */
    data class Tier(val packId: String, val label: String, val errorPct: Double) {
        val accuracy: Int get() = (100 - errorPct).roundToInt()
        // From the shown (rounded) accuracy, so badge and number always agree. "Excellent", not
        // "Perfect": even 98% gets ~2 words in 100 wrong.
        val quality: Quality get() = when {
            accuracy >= 98 -> Quality.EXCELLENT
            accuracy >= 92 -> Quality.GREAT
            accuracy >= 88 -> Quality.GOOD
            accuracy >= 82 -> Quality.OK
            else -> Quality.EXPERIMENTAL
        }
    }

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
    private const val DOLPHIN_B = "csukuangfj/sherpa-onnx-dolphin-base-ctc-multi-lang-int8-2025-04-02"
    private const val DOLPHIN_B_REV = "1f3a53d0ecf658f8b0974e2cfde368eee40732fa"
    private const val OMNI3 = "csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12"
    private const val OMNI3_REV = "6abf1ece20cd2308bdb7d13cd78ec1c44fa4c094"
    private const val KROKO_ES = "csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06"
    private const val KROKO_ES_REV = "20cf7a4921613397841d31168796cade5b866585"
    private const val KROKO_FR = "csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06"
    private const val KROKO_FR_REV = "08b84b7b7cf519be9817e9c16919d96a7a8bad91"
    private const val KROKO_DE = "csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06"
    private const val KROKO_DE_REV = "887db3d083240198c2d2b99fb66cfcfe6948ced8"
    private const val RU_SMALL = "csukuangfj/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16"
    private const val RU_SMALL_REV = "31fa603e4f31279c6e1f7600fed13dc4312663ab"
    private const val ZH_STREAM = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30"
    private const val ZH_STREAM_REV = "ad658fa0201659a09ea3c176129a191c77ecae8f"
    private const val ZH_SMALL = "csukuangfj/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01"
    private const val ZH_SMALL_REV = "a5f60fe00dcfbaf68fcc1c6b5cf53061e144d6da"
    private const val ML8 = "csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10"
    private const val ML8_REV = "c6726c1147387ad2a11148b33973135d92a55e6c"
    private const val ML8_F = "epoch-75-avg-11-chunk-16-left-128"
    private const val WBASE = "csukuangfj/sherpa-onnx-whisper-base"
    private const val WBASE_REV = "bb53ee204431c90d314c1cc08d28d23e5b7927cc"
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
            title = "Canary 180M (English, French)",
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
            title = "Omnilingual 1B (Thai, Indonesian, Hindi, Turkish, Filipino)",
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
            id = "dolphin-base",
            title = "Dolphin base (light: Thai, Indonesian, Japanese, Korean, Cantonese)",
            kind = Kind.DOLPHIN_CTC,
            files = listOf(
                hf(DOLPHIN_B, DOLPHIN_B_REV, "model.int8.onnx", 103_729_802, "a3aa46c97f3f60f135ff949793cb05fabe7a0b3c484dc2e3cc699d354ee11b76"),
                hf(DOLPHIN_B, DOLPHIN_B_REV, "tokens.txt", 504_662, "c3788261a51df1899ea4b210b552cd42139204de72c0ad60f6cebb199078872e"),
            ),
            ramMb = 370,
            credit = "DataoceanAI Dolphin (Apache-2.0)",
        ),
        Pack(
            id = "omnilingual-300m",
            title = "Omnilingual 300M (light: Hindi, Filipino)",
            kind = Kind.OMNILINGUAL_CTC,
            files = listOf(
                hf(OMNI3, OMNI3_REV, "model.int8.onnx", 365_352_120, "e7c4e54ee4c4c47829cc6667d5d00ed8ea7bef1dcfeef0fce766f77752a2726c"),
                hf(OMNI3, OMNI3_REV, "tokens.txt", 86_423, "a7a044c52cb29cbe8b0dc1953e92cefd4ca16b0ed968177b6beab21f9a7d0b31"),
            ),
            ramMb = 900,
            credit = "Meta Omnilingual ASR (Apache-2.0)",
        ),
        Pack(
            id = "kroko-es",
            title = "Kroko (Spanish, light)",
            kind = Kind.ONLINE_TRANSDUCER,
            files = listOf(
                hf(KROKO_ES, KROKO_ES_REV, "encoder.onnx", 154_878_102, "2d9f5ef87d1a5257f8a6687e21501c56f3aa2fcbfcfab9364dcc4ce4e06ae81b"),
                hf(KROKO_ES, KROKO_ES_REV, "decoder.onnx", 617_488, "d4ce176b94b25f7acc88717bc3f704fcf5d6e131aaac2e0cabab3885541181ee"),
                hf(KROKO_ES, KROKO_ES_REV, "joiner.onnx", 336_817, "dae35df88d676e320fcdb99217328e66dcf722bf11b0f2459e14ddb5b982ded5"),
                hf(KROKO_ES, KROKO_ES_REV, "tokens.txt", 6_385, "1be5e0a58e05d06d327df4c6b7b5e4f8aba01da6981eb016fcaceafc6a56680f"),
            ),
            ramMb = 270,
            credit = "Banafo Kroko (CC-BY-SA-4.0)",
        ),
        Pack(
            id = "kroko-fr",
            title = "Kroko (French, light)",
            kind = Kind.ONLINE_TRANSDUCER,
            files = listOf(
                hf(KROKO_FR, KROKO_FR_REV, "encoder.onnx", 70_092_599, "e02facae1daf6f1f13da67ea3ace7c722516d0868d1768d78c0580bc22cc0c5b"),
                hf(KROKO_FR, KROKO_FR_REV, "decoder.onnx", 617_488, "6aed547570e3ab5afc05429a017cedd3a056c16df3baa5703f02461cefa25bac"),
                hf(KROKO_FR, KROKO_FR_REV, "joiner.onnx", 336_817, "a51eec759bcdcaae2614686fa2a8b57417b2d420dd55a5a5558b388d35a9b2b6"),
                hf(KROKO_FR, KROKO_FR_REV, "tokens.txt", 5_415, "fedfb9c844bfb2bf14171f8184863e3d617b815a8667bdd9fc9a3149fde73298"),
            ),
            ramMb = 160,
            credit = "Banafo Kroko (CC-BY-SA-4.0)",
        ),
        Pack(
            id = "kroko-de",
            title = "Kroko (German, light)",
            kind = Kind.ONLINE_TRANSDUCER,
            files = listOf(
                hf(KROKO_DE, KROKO_DE_REV, "encoder.onnx", 70_091_557, "6e83993d6967ec7a3498b055b7e85ace85b5d64d1b1e8773cb29a43a11f5edb5"),
                hf(KROKO_DE, KROKO_DE_REV, "decoder.onnx", 617_489, "94a29592b403c53fa2231b478637da1ab4abcef7f5e46e432098416a4a3ed562"),
                hf(KROKO_DE, KROKO_DE_REV, "joiner.onnx", 336_817, "28356bff070aea51ab1d725a3278e81d19f9300f860d3248a7014292264df15a"),
                hf(KROKO_DE, KROKO_DE_REV, "tokens.txt", 5_606, "86e8370994ff2c01149ba8c4f8709aa93cdc18914b27a717e291e96faf39a6eb"),
            ),
            ramMb = 160,
            credit = "Banafo Kroko (CC-BY-SA-4.0)",
        ),
        Pack(
            id = "ru-small",
            title = "Small streaming (Russian, light)",
            kind = Kind.ONLINE_TRANSDUCER,
            files = listOf(
                hf(RU_SMALL, RU_SMALL_REV, "encoder.int8.onnx", 26_214_060, "e0db705e94ec35d803b1df4f40cda23d064e1142977c80ab288430b109777a9d"),
                hf(RU_SMALL, RU_SMALL_REV, "decoder.onnx", 2_093_080, "89b3088a9e20e1ef7f2e85ce1a3478afe6a9c4ac57369cabcc4beb8e95328ea0"),
                hf(RU_SMALL, RU_SMALL_REV, "joiner.int8.onnx", 259_417, "b55784b071ab7512eab4c7c44e4f5478284ef33c83562cc6a249b972515a31e5"),
                hf(RU_SMALL, RU_SMALL_REV, "tokens.txt", 6_388, "93bbbc0bae6b78c0bbb743d4aa9fded3bb5ff3aac5f0200e3a769a5a05e0fdf6"),
            ),
            ramMb = 100,
            credit = "Alpha Cephei Vosk small zipformer (Apache-2.0)",
        ),
        Pack(
            id = "zh-stream",
            title = "Streaming zipformer (Chinese)",
            kind = Kind.ONLINE_TRANSDUCER,
            files = listOf(
                hf(ZH_STREAM, ZH_STREAM_REV, "encoder.int8.onnx", 161_141_793, "5ac51e27981bb4dab01bb9be4958453ba50c3b61c063ddda0eab23fd3671aa4f"),
                hf(ZH_STREAM, ZH_STREAM_REV, "decoder.onnx", 5_165_083, "06522ad63cec0fdf6809f4e1db9bb4f7d710c34582e3b35db62ac60eccafac7e"),
                hf(ZH_STREAM, ZH_STREAM_REV, "joiner.int8.onnx", 1_033_416, "b34584dc6f561089e1d747fedebb3765f2caa72c927ef54d7ca55e5ae40a814b"),
                hf(ZH_STREAM, ZH_STREAM_REV, "tokens.txt", 20_628, "6193c7ea1c96d0d9a1e9652789b40d13a8a913b434a5451e93158f5a09fd6652"),
            ),
            ramMb = 250,
            credit = "k2-fsa streaming Zipformer (Apache-2.0)",
        ),
        Pack(
            id = "zh-small",
            title = "Small streaming (Chinese, light)",
            kind = Kind.ONLINE_CTC,
            files = listOf(
                hf(ZH_SMALL, ZH_SMALL_REV, "model.int8.onnx", 26_342_340, "68c9c943840f7d9cf3e8a4970ba50f404feb5277f611fa82b7e72267786fa84a"),
                hf(ZH_SMALL, ZH_SMALL_REV, "tokens.txt", 13_366, "6fed8c6c248516f38e7faa19404b57413e8ce259f1cbc1fa4aebc86eac32fdfd"),
            ),
            ramMb = 100,
            credit = "k2-fsa streaming Zipformer (Apache-2.0)",
        ),
        Pack(
            id = "ml8-stream",
            title = "8-language streaming (Indonesian)",
            kind = Kind.ONLINE_TRANSDUCER,
            files = listOf(
                hf(ML8, ML8_REV, "encoder-$ML8_F.int8.onnx", 296_583_597, "f9001ed7a9e46d0294438c1a30cd7c72d1cc4bdd4e7880edbcda36f67081e32e"),
                hf(ML8, ML8_REV, "decoder-$ML8_F.onnx", 33_837_085, "7ebc63f34b21c8efb4a41a5a2eee7fe1448829ce0230ecc5369e67fc14d90d48"),
                hf(ML8, ML8_REV, "joiner-$ML8_F.int8.onnx", 8_257_421, "db88e3172323551abaa99b91b18fb422a27ea4a834fd0db10389f9478816f917"),
                hf(ML8, ML8_REV, "tokens.txt", 195_244, "784f24950f6bcce1b0021035632dd60fd4617ecd8ca0581ab57d7b39d77ba5ab"),
            ),
            ramMb = 460,
            credit = "k2-fsa streaming Zipformer (Apache-2.0)",
        ),
        Pack(
            id = "whisper-base",
            title = "Whisper base (Portuguese, light)",
            kind = Kind.WHISPER,
            files = listOf(
                hf(WBASE, WBASE_REV, "base-encoder.int8.onnx", 29_120_534, "0b8fb1304b6109976038efff5ace81720e00386f3ff6b54ee8c75291ca0a1e11"),
                hf(WBASE, WBASE_REV, "base-decoder.int8.onnx", 130_672_026, "9759d217388a01b3a4c7c15533201067b48ae819c4daafc8624e64b9409dc02d"),
                hf(WBASE, WBASE_REV, "base-tokens.txt", 816_730, "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
            ),
            ramMb = 560,
            credit = "OpenAI Whisper (MIT)",
            slow = true,
        ),
    ).associateBy { it.id }

    /**
     * Languages offered right now, each with its tiers biggest-first: Large / Medium / Small
     * (named by size, the real trade-off; quality shows as the measured accuracy next to
     * it, so a name never contradicts the quality label as "Best · OK" did).
     * Accuracy + quality come from the shoot-out (docs/systems/voice-to-text.md). Parakeet-25
     * is only used where it never wrote the wrong language; Canary takes the language
     * explicitly, so it can't confuse them.
     */
    val languages: List<Lang> = listOf(
        Lang("en", "English", "English", listOf(
            Tier("parakeet-en", "Large", 7.8),
            Tier("canary-180m", "Medium", 16.7),
            Tier("kroko-en", "Small", 19.8),
        )),
        // Re-measured on 200 FLEURS clips (tools/speech-bench/compare.py): Canary-180M tied
        // Kroko-es (7.0 vs 7.1), so the lighter one is the only small size.
        Lang("es", "Spanish", "Español", listOf(
            Tier("parakeet-25", "Large", 5.0),
            Tier("kroko-es", "Small", 7.1),
        )),
        Lang("pt", "Portuguese", "Português", listOf(
            Tier("parakeet-25", "Large", 4.5),
            Tier("whisper-base", "Small", 15.3),
        )),
        // GigaAM v3 is both the most accurate (6.2% vs Parakeet-25's 10.6%) and the
        // lightest full model; the 29 MB streaming one is the Small tier.
        Lang("ru", "Russian", "Русский", listOf(
            Tier("gigaam3-ru", "Large", 6.2),
            Tier("ru-small", "Small", 14.1),
        )),
        // Re-measured on 200 clips: Canary-180M (8.9) tied both neighbours, so it went
        // (user-reported: Large and Medium showed the same accuracy).
        Lang("de", "German", "Deutsch", listOf(
            Tier("parakeet-25", "Large", 8.4),
            Tier("kroko-de", "Small", 9.9),
        )),
        Lang("fr", "French", "Français", listOf(
            Tier("canary-180m", "Large", 9.7),
            Tier("kroko-fr", "Small", 12.9),
        )),
        // Parakeet-25 never answered in the wrong language for these (shoot-out check).
        Lang("it", "Italian", "Italiano", listOf(Tier("parakeet-25", "Standard", 9.0))),
        Lang("bg", "Bulgarian", "Български", listOf(Tier("parakeet-25", "Standard", 11.9))),
        Lang("pl", "Polish", "Polski", listOf(Tier("parakeet-25", "Standard", 13.7))),
        Lang("uk", "Ukrainian", "Українська", listOf(Tier("parakeet-25", "Standard", 14.2))),
        Lang("nl", "Dutch", "Nederlands", listOf(Tier("parakeet-25", "Standard", 14.7))),
        Lang("zh", "Chinese (Mandarin)", "中文", listOf(
            Tier("sensevoice", "Large", 8.0),
            Tier("zh-stream", "Medium", 14.4),
            Tier("zh-small", "Small", 17.5),
        )),
        Lang("yue", "Cantonese", "粵語", listOf(
            Tier("sensevoice", "Large", 5.5),
            Tier("dolphin-base", "Small", 12.3),
        )),
        Lang("ja", "Japanese", "日本語", listOf(
            Tier("reazon-ja", "Large", 5.5),
            Tier("dolphin-base", "Small", 17.6),
        )),
        Lang("ko", "Korean", "한국어", listOf(
            Tier("sensevoice", "Large", 7.2),
            Tier("dolphin-base", "Small", 11.6),
        )),
        Lang("vi", "Vietnamese", "Tiếng Việt", listOf(Tier("zipformer-vi", "Standard", 10.8))),
        // Re-measured on 200 clips: the 8-language streaming model (19.0) tied Dolphin base
        // (17.7), which is lighter, so Thai has no Medium size.
        Lang("th", "Thai", "ไทย", listOf(
            Tier("omnilingual-1b", "Large", 12.4),
            Tier("dolphin-base", "Small", 17.7),
        )),
        // Re-measured on 200 clips: all three differ for real (paired bootstrap).
        Lang("id", "Indonesian", "Bahasa Indonesia", listOf(
            Tier("omnilingual-1b", "Large", 14.2),
            Tier("ml8-stream", "Medium", 16.2),
            Tier("dolphin-base", "Small", 23.9),
        )),
        Lang("hi", "Hindi", "हिन्दी", listOf(
            Tier("omnilingual-1b", "Large", 11.4),
            Tier("omnilingual-300m", "Small", 22.5),
        )),
        Lang("tr", "Turkish", "Türkçe", listOf(Tier("omnilingual-1b", "Standard", 14.9))),
        Lang("fil", "Filipino", "Filipino", listOf(
            Tier("omnilingual-1b", "Large", 16.5),
            Tier("omnilingual-300m", "Small", 22.4),
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
