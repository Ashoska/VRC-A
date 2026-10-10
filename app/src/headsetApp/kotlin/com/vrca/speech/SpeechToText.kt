package com.vrca.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Headset OFFLINE voice-to-text engine (sherpa-onnx). Phrase-based: a voice-activity
 * detector (Silero) cuts the mic stream into phrases at your pauses, and each finished
 * phrase is recognised by the language's pack (e.g. Parakeet for English) and delivered
 * via [Listener.onFinal]. So the chatbox only ever receives finished sentences, never
 * half-guessed words that keep changing.
 *
 * Two threads: the capture thread never blocks (mic → gain → VAD), and a decode thread
 * runs recognition per phrase, so no speech is dropped while a phrase is processed.
 * The recognizer (~1 GB for Parakeet) lives in RAM only while listening.
 *
 * Signature MUST match the publicApp/adminApp stubs (SUPPORTED=false there).
 */
object SpeechToText {
    const val SUPPORTED = true

    private const val TAG = "SpeechToText"
    private const val SAMPLE_RATE = 16000
    private const val DECODE_THREADS = 2

    interface Listener {
        /** Unused in phrase mode (kept for API compatibility). */
        fun onPartial(text: String) {}
        /** A finished phrase. */
        fun onFinal(text: String)
        fun onError(message: String)
        /** True while the voice detector hears you speaking. */
        fun onSpeechActive(active: Boolean) {}
        /** True while the model loads (a few seconds on first start). */
        fun onLoading(loading: Boolean) {}
    }

    @Volatile private var running = false
    @Volatile private var capture: Thread? = null

    fun isListening(): Boolean = running

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, langCode: String, listener: Listener): Boolean {
        if (running) return true
        val app = ctx.applicationContext
        val pack = SpeechPacks.selectedPack(app, langCode)
        if (pack == null || !SpeechPacks.isLanguageReady(app, langCode)) {
            listener.onError("Voice pack for this language isn't installed yet."); return false
        }
        running = true
        val phrases = LinkedBlockingQueue<FloatArray>()
        val captureDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread({
            var rec: PhraseDecoder? = null
            var vad: Vad? = null
            var audio: AudioRecord? = null
            var decoder: Thread? = null
            try {
                listener.onLoading(true)
                rec = phraseDecoder(app, pack, langCode)
                vad = Vad(config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = SpeechPacks.filePath(app, SpeechCatalog.VAD.id, "silero_vad.onnx"),
                        threshold = 0.5f,
                        minSilenceDuration = 0.5f, // pause that ends a phrase
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 15f,  // long monologues are split, never truncated
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ))
                listener.onLoading(false)

                val r = rec
                decoder = Thread({
                    // Runs until capture has flushed its last phrase AND the queue is drained,
                    // so words spoken right before Stop are never lost.
                    while (!captureDone.get() || phrases.isNotEmpty()) {
                        val seg = phrases.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        val text = runCatching { r.text(normalize(seg)) }.getOrElse { Log.w(TAG, "decode", it); "" }
                        if (text.isNotEmpty()) listener.onFinal(text)
                    }
                }, "stt-decode").apply { isDaemon = true; start() }

                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
                if (audio.state != AudioRecord.STATE_INITIALIZED) { listener.onError("Microphone unavailable."); return@Thread }
                audio.startRecording()

                val pcm = ShortArray(512) // 32 ms windows
                val gain = Agc()
                var wasSpeech = false
                while (running) {
                    val n = audio.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    val f = FloatArray(n) { pcm[it] / 32768f }
                    gain.apply(f)
                    vad.acceptWaveform(f)
                    while (!vad.empty()) { phrases.offer(vad.front().samples); vad.pop() }
                    val speech = vad.isSpeechDetected()
                    if (speech != wasSpeech) { wasSpeech = speech; listener.onSpeechActive(speech) }
                }
                vad.flush() // finish a phrase cut off by Stop
                while (!vad.empty()) { phrases.offer(vad.front().samples); vad.pop() }
                if (wasSpeech) listener.onSpeechActive(false)
            } catch (e: Throwable) {
                Log.e(TAG, "dictation error", e)
                listener.onLoading(false)
                listener.onError(describe(e))
            } finally {
                running = false
                captureDone.set(true)
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                runCatching { decoder?.join(15_000) } // let queued phrases finish
                runCatching { vad?.release() }
                runCatching { rec?.release() }
            }
        }, "stt-capture")
        t.isDaemon = true
        capture = t
        t.start()
        return true
    }

    fun stop() {
        running = false
        capture = null
    }

    /** Recognises one finished phrase. One implementation per model family. */
    private interface PhraseDecoder {
        fun text(seg: FloatArray): String
        fun release()
    }

    private fun phraseDecoder(ctx: Context, pack: SpeechCatalog.Pack, langCode: String): PhraseDecoder {
        // File names differ per pack (e.g. Parakeet ships int8 decoders, GigaAM fp32),
        // so resolve them from the catalog by prefix.
        fun p(prefix: String) = SpeechPacks.filePath(ctx, pack.id, pack.files.first { it.name.startsWith(prefix) }.name)
        return when (pack.kind) {
            SpeechCatalog.Kind.NEMO_TRANSDUCER -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(encoder = p("encoder"), decoder = p("decoder"), joiner = p("joiner")),
                    tokens = p("tokens"), numThreads = DECODE_THREADS, modelType = "nemo_transducer",
                ),
                decodingMethod = "greedy_search",
            ))
            SpeechCatalog.Kind.CANARY -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 128, dither = 0f),
                modelConfig = OfflineModelConfig(
                    // The language is set explicitly, so Canary can never answer in another one.
                    canary = OfflineCanaryModelConfig(encoder = p("encoder"), decoder = p("decoder"),
                        srcLang = langCode, tgtLang = langCode, usePnc = true),
                    tokens = p("tokens"), numThreads = DECODE_THREADS,
                ),
                decodingMethod = "greedy_search",
            ))
            SpeechCatalog.Kind.ONLINE_TRANSDUCER -> {
                val rec = OnlineRecognizer(config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(encoder = p("encoder"), decoder = p("decoder"), joiner = p("joiner")),
                        tokens = p("tokens"), numThreads = DECODE_THREADS,
                    ),
                    enableEndpoint = false,
                    decodingMethod = "greedy_search",
                ))
                val tail = FloatArray((SAMPLE_RATE * 0.8f).toInt()) // flush the model's look-ahead
                object : PhraseDecoder {
                    override fun text(seg: FloatArray): String {
                        val s = rec.createStream("")
                        try {
                            s.acceptWaveform(seg, SAMPLE_RATE)
                            s.acceptWaveform(tail, SAMPLE_RATE)
                            s.inputFinished()
                            while (rec.isReady(s)) rec.decode(s)
                            return rec.getResult(s).text.trim()
                        } finally { s.release() }
                    }
                    override fun release() = rec.release()
                }
            }
        }
    }

    private fun offline(config: OfflineRecognizerConfig): PhraseDecoder {
        val rec = OfflineRecognizer(config = config)
        return object : PhraseDecoder {
            override fun text(seg: FloatArray): String {
                val s = rec.createStream()
                try {
                    s.acceptWaveform(seg, SAMPLE_RATE)
                    rec.decode(s)
                    return rec.getResult(s).text.trim()
                } finally { s.release() }
            }
            override fun release() = rec.release()
        }
    }

    /** Normalise a phrase's loudness (~-24 dBFS): quiet mics hurt recognition badly. */
    private fun normalize(seg: FloatArray): FloatArray {
        var sum = 0.0
        for (v in seg) sum += v * v
        val rms = sqrt(sum / seg.size.coerceAtLeast(1)).toFloat()
        if (rms > 1e-4f) {
            val g = (0.063f / rms).coerceAtMost(30f)
            var peak = 0f
            for (i in seg.indices) { seg[i] *= g; if (kotlin.math.abs(seg[i]) > peak) peak = kotlin.math.abs(seg[i]) }
            if (peak > 0.97f) { val k = 0.97f / peak; for (i in seg.indices) seg[i] *= k }
        }
        return seg
    }

    /** Slow automatic gain so a quiet headset mic still triggers the voice detector. */
    private class Agc {
        private var level = 0.03f
        private var gain = 1f
        fun apply(x: FloatArray) {
            var sum = 0.0
            for (v in x) sum += v * v
            val rms = sqrt(sum / x.size).toFloat()
            if (rms > 0.002f) level = level * 0.97f + rms * 0.03f // track speech-ish level only
            val target = (0.063f / level).coerceIn(1f, 8f)
            gain += (target - gain) * 0.05f
            for (i in x.indices) x[i] = (x[i] * gain).coerceIn(-1f, 1f)
        }
    }

    /** Exception type + cause chain (a bare message hides native-load failures). */
    private fun describe(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            if (depth > 0) sb.append(" <- ")
            sb.append(cur.javaClass.simpleName)
            cur.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
            cur = cur.cause; depth++
        }
        return sb.toString().ifBlank { "speech error" }
    }
}
