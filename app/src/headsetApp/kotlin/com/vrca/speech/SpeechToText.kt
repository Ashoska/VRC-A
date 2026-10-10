package com.vrca.speech

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
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
    /** "Hearing you" ends this many 32 ms windows (~0.4 s) after the voice stops. */
    private const val QUIET_WINDOWS = 12
    /** Audio kept before / after the VAD's cut of a phrase (samples). */
    // 0.5 s: words starting with a soft consonant ("finally", "forever") were cut at
    // 0.25 s; never reaches back into the previous phrase (clamped to its end).
    private const val PRE_ROLL = SAMPLE_RATE / 2L   // 0.5 s
    private const val POST_ROLL = SAMPLE_RATE / 5L  // 0.2 s
    /** Free memory that must remain above Android's low-memory line after loading a model. */
    /** Written without spaces between words (live words join without them). */
    private val NO_SPACE = setOf("zh", "yue", "ja")
    /** Live words: re-read the unfinished phrase at most this often / at least this often. */
    private const val LIVE_MIN_MS = 800L
    private const val LIVE_MAX_MS = 5_000L
    /** No live re-reads past this phrase length (the final arrives by 15 s anyway). */
    private const val LIVE_MAX_SEC = 12
    /** Audio kept for re-reads, and how far before the VAD's trigger a phrase starts. */
    private const val RING_SEC = 20
    private const val PREROLL_MS = 500
    // Live words wait for this much voice (~0.2 s): a cough or hum used to flash a
    // made-up "okay" before its final took it back.
    private const val LIVE_MIN_VOICED = 6

    interface Listener {
        /**
         * Live words: the unfinished sentence so far (re-read about once a second while you
         * talk). The next [onFinal] replaces it; an empty final means "it was nothing, remove it".
         */
        fun onPartial(text: String, pauseBeforeSec: Float) {}
        /**
         * A finished phrase. [pauseBeforeSec] = silence since the previous phrase ended
         * (infinite for the first), so the caller can tell a mid-sentence breath from a
         * new sentence. [decodeMs] = time the model took on it.
         */
        fun onFinal(text: String, pauseBeforeSec: Float, decodeMs: Long)
        fun onError(message: String)
        /** True while you're speaking (voiced speech, not breathing or clicks). */
        fun onSpeechActive(active: Boolean) {}
        /** True while the model loads (a few seconds on first start). */
        fun onLoading(loading: Boolean) {}
        /** Listening ended for any reason (Stop, the notification's Stop, an error). */
        fun onStopped() {}
        /** A voice command fired (VoiceCommands: said on its own, passed every check).
         *  PAUSE/RESUME only fire when they change something; CLEAR is the caller's call. */
        fun onCommand(command: VoiceCommand) {}
    }

    @Volatile private var running = false
    @Volatile private var capture: Thread? = null

    fun isListening(): Boolean = running

    @SuppressLint("MissingPermission")
    fun start(ctx: Context, langCode: String, listener: Listener): Boolean {
        if (running) return true
        // The last session is still winding down (decoder draining, model being freed):
        // starting now loaded a second model next to it (headset lag on fast mic taps), and
        // its ending then stopped the new one.
        if (capture != null) { listener.onError("Still stopping, try again in a moment."); return false }
        val app = ctx.applicationContext
        val pack = SpeechPacks.selectedPack(app, langCode)
        if (pack == null || !SpeechPacks.isLanguageReady(app, langCode)) {
            listener.onError("Voice pack for this language isn't installed yet."); return false
        }
        // Refuse only a model that can't fit in the memory Android reports as available
        // (that already counts what it can reclaim). Adding the low-memory line + a margin
        // on top refused packs that fit fine (user-reported: "pick a lighter tier" with
        // enough RAM). Under pressure Android reclaims a background app like us first,
        // never VRChat in front.
        val mem = ActivityManager.MemoryInfo()
        runCatching { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem) }
        val freeMb = mem.availMem / (1024 * 1024)
        val needMb = pack.ramMb + 50L
        if (mem.availMem > 0 && freeMb < needMb) {
            listener.onError("Not enough free memory for this voice model (needs about $needMb MB, $freeMb MB free). Pick a lighter tier, or close other apps.")
            return false
        }
        running = true
        voicePaused = false; micIgnoreUntil = 0L; talkStartedAt = 0L
        // Keep the mic live when VRC-A goes to the background (we're on screen now: the
        // user just tapped the mic, which is when Android allows this start).
        DictationService.start(app)
        val phrases = LinkedBlockingQueue<Segment>()
        val live = LiveState()
        val captureDone = java.util.concurrent.atomic.AtomicBoolean(false)
        val t = Thread({
            var rec: PhraseDecoder? = null
            var vad: Vad? = null
            var audio: AudioRecord? = null
            var decoder: Thread? = null
            try {
                listener.onLoading(true)
                // Created at background priority: ONNX Runtime's worker threads inherit it,
                // so decoding never outranks VRChat. Capture itself then runs at audio
                // priority (it must never miss mic data).
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                rec = phraseDecoder(app, pack, langCode)
                var level = sensitivity
                fun newVad(l: MicSensitivity) = Vad(config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = SpeechPacks.filePath(app, SpeechCatalog.VAD.id, "silero_vad.onnx"),
                        // Per sensitivity (MicSensitivity): 0.6 stopped breath triggers but
                        // missed soft words; Normal is 0.5 now that Voicing filters breaths.
                        threshold = l.vadThreshold,
                        // Pause that ends a phrase. Short on purpose (text shows sooner); a
                        // mid-sentence breath no longer adds a full stop (the caller joins
                        // phrases by pauseBeforeSec).
                        minSilenceDuration = 0.4f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 15f,  // long monologues are split, never truncated
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                ))
                vad = newVad(level)
                listener.onLoading(false)
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) }

                val r = rec
                decoder = Thread({
                    // Background priority: VRChat always gets the CPU first; under load live
                    // words just update less often instead of the game stuttering.
                    runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                    // Runs until capture has flushed its last phrase AND the queue is drained,
                    // so words spoken right before Stop are never lost. Finished phrases always
                    // go first; live re-reads (partials) only run when none is waiting.
                    var lastEnd = -1L // end sample of the last phrase that produced text
                    var partialShown = false
                    var agreement: LiveAgreement? = null
                    var agreementFor = -1L
                    val noSpace = langCode in NO_SPACE
                    // The wearer's usual voice level (recent dictated phrases): a command must be
                    // about this loud, so other people / VRChat's speakers can't fire one.
                    val levels = ArrayDeque<Float>()
                    fun loudEnough(level: Float): Boolean {
                        if (levels.size < 3) return true
                        val median = levels.sorted()[levels.size / 2]
                        return level >= median * VoiceCommands.MIN_LEVEL_RATIO
                    }
                    fun pauseFrom(start: Long) =
                        if (lastEnd < 0) Float.POSITIVE_INFINITY else (start - lastEnd).coerceAtLeast(0L) / SAMPLE_RATE.toFloat()
                    // A finished phrase going in as text (unless it holds nothing, or paused by voice).
                    fun deliver(seg: Segment, raw: String, pause: Float, ms: Long) {
                        // Made-up fillers ("okay" from a rustle) count as nothing.
                        val text = if (raw.isNotEmpty() && SpeechFilter.keep(raw, seg.voiced, seg.minVoiced, noSpace)) raw else ""
                        if (text.isEmpty() || seg.epoch != epoch || voicePaused) {
                            // Nothing there: take back live words shown for it, if any.
                            if (partialShown) { partialShown = false; listener.onFinal("", pause, ms) }
                            return
                        }
                        lastEnd = seg.end
                        partialShown = false
                        if (seg.level > 0f) { levels.addLast(seg.level); if (levels.size > 20) levels.removeFirst() }
                        listener.onFinal(text, pause, ms)
                    }
                    // Fire a command that passed every check, if it changes anything.
                    fun fire(cmd: VoiceCommand) {
                        when (cmd) {
                            VoiceCommand.PAUSE -> if (voicePaused) return else voicePaused = true
                            VoiceCommand.RESUME -> if (!voicePaused) return else voicePaused = false
                            VoiceCommand.CLEAR -> {}
                        }
                        micIgnoreUntil = android.os.SystemClock.elapsedRealtime() + CommandSounds.MIC_IGNORE_MS
                        listener.onCommand(cmd)
                    }
                    // A command word waiting to see whether you carry on talking.
                    class Pending(val cmd: VoiceCommand, val seg: Segment, val raw: String, val pause: Float, val ms: Long, val at: Long)
                    var pending: Pending? = null
                    while (!captureDone.get() || phrases.isNotEmpty()) {
                        pending?.let { pc ->
                            when {
                                // Kept talking ("Pause! wait…"): it was conversation, in as text.
                                talkStartedAt > pc.at -> { pending = null; deliver(pc.seg, pc.raw, pc.pause, pc.ms) }
                                android.os.SystemClock.elapsedRealtime() - pc.at >= VoiceCommands.CONFIRM_MS -> { pending = null; fire(pc.cmd) }
                                // Undecided: hold everything else back so the order stays right.
                                else -> { Thread.sleep(20); return@let }
                            }
                        }
                        if (pending != null) continue
                        val seg = phrases.poll(50, TimeUnit.MILLISECONDS)
                        if (seg == null) {
                            val job = live.pending.getAndSet(null) ?: continue
                            val t0 = System.nanoTime()
                            val hyp = runCatching { r.hyp(normalize(job.samples)) }.getOrNull()
                            val ms = (System.nanoTime() - t0) / 1_000_000
                            // Lock in words two re-reads agree on; re-read only after them next.
                            val text = when {
                                hyp == null -> ""
                                !r.hasTimes || hyp.tokens.isEmpty() -> hyp.text
                                else -> {
                                    if (agreementFor != job.start) { agreement = LiveAgreement(noSpace); agreementFor = job.start }
                                    val a = agreement!!
                                    a.update(hyp.tokens, hyp.times, job.chunkSec).also { live.cutSec = a.cutSec }
                                }
                            }
                            // Pace re-reads by what they cost on THIS device: wait ~2.5x a
                            // re-read, so live words never take over the CPU next to VRChat.
                            live.intervalMs = (ms * 5 / 2).coerceIn(LIVE_MIN_MS, LIVE_MAX_MS)
                            // Drop it if its phrase finished meanwhile (the final is next), and
                            // hold back words that could still be a command (never shown).
                            val cmds = commands
                            if (text.isNotEmpty() && job.seq == live.finalsQueued.get() && !voicePaused &&
                                (cmds == null || !VoiceCommands.couldBe(text, cmds))
                            ) {
                                partialShown = true
                                listener.onPartial(text, pauseFrom(job.start))
                            }
                            continue
                        }
                        agreement = null; agreementFor = -1L
                        val stale = seg.epoch != epoch // spoken before Clear
                        val t0 = System.nanoTime()
                        val raw = if (seg.noise || stale) "" else runCatching { r.text(normalize(seg.samples)) }.getOrElse { Log.w(TAG, "decode", it); "" }
                        val ms = (System.nanoTime() - t0) / 1_000_000
                        val pause = pauseFrom(seg.start)
                        // A command word on its own, after a quiet moment, clearly voiced and
                        // as loud as you normally talk: wait to see you don't carry on.
                        val cmds = commands
                        val cmd = if (cmds != null && !stale && raw.isNotEmpty()) VoiceCommands.match(raw, cmds) else null
                        if (cmd != null && pause >= VoiceCommands.QUIET_BEFORE_SEC &&
                            seg.voiced >= VoiceCommands.MIN_VOICED && loudEnough(seg.level)
                        ) {
                            if (partialShown) { partialShown = false; listener.onFinal("", pause, ms) }
                            pending = Pending(cmd, seg, raw, pause, ms, android.os.SystemClock.elapsedRealtime())
                            continue
                        }
                        deliver(seg, raw, pause, ms)
                    }
                }, "stt-decode").apply { isDaemon = true; start() }

                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                audio = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, SAMPLE_RATE * 2))
                if (audio.state != AudioRecord.STATE_INITIALIZED) { listener.onError("Microphone unavailable."); return@Thread }
                audio.startRecording()

                val pcm = ShortArray(512) // 32 ms windows
                val gain = Agc()
                var voicedRun = 0      // voiced windows inside the current VAD speech
                var sinceVoiced = 1000 // windows since the last voiced one
                var shown = false      // what onSpeechActive last reported
                var heard = true       // the listen trigger's state as last applied
                var wasTalking = false
                // Live words: the last RING_SEC of (gained) audio, so the unfinished phrase can
                // be re-read; total = samples written, phraseStart = where it began (-1: none).
                val ring = FloatArray(SAMPLE_RATE * RING_SEC)
                var total = 0L
                var phraseStart = -1L
                var lastPartialAt = 0L
                var lastSegEnd = 0L // VAD end of the last phrase: pre-roll never reaches before it
                // The VAD counts samples from when it was built; vadBase = `total` then, so
                // vadBase + its positions index the ring. It must be fed EVERY sample (silence
                // while the gate is closed): skipping audio shifted every later phrase onto the
                // wrong stretch of the ring (user-reported: after "pause"/"resume" finals
                // lost their last words and replaced correct live words, until a restart).
                var vadBase = 0L
                val silence = FloatArray(pcm.size)
                // [flushEnd]: after flush(), the real end of the phrase cut short (the VAD's
                // flush drops its last minSilenceDuration, which then held real speech).
                fun drain(flushEnd: Long = -1L) {
                    val v = vad ?: return
                    while (!v.empty()) {
                        val s = v.front(); v.pop()
                        // Short weak blips (breath, click) have no voice: don't spend the model
                        // on them. How short/weak is the sensitivity's call: longer quiet
                        // phrases always reach the model. Still queued as "noise" so live
                        // words shown for it get taken back.
                        val dur = s.samples.size / SAMPLE_RATE.toFloat()
                        val (voicedCount, voicedRms) = Voicing.voicedStats(s.samples)
                        val noise = voicedCount < level.minVoiced && dur < level.blipMaxSec
                        // A little audio before and after the VAD's cut (still in the ring):
                        // it reacts late and ends early, clipping soft first/last syllables.
                        val segStart = vadBase + s.start.toLong()
                        val segEnd = if (flushEnd >= 0 && v.empty()) maxOf(segStart + s.samples.size, flushEnd) else segStart + s.samples.size
                        val from = maxOf(segStart - PRE_ROLL, lastSegEnd, total - ring.size, 0L)
                        val to = minOf(segEnd + POST_ROLL, total)
                        val padded = if (from <= segStart && to >= segEnd)
                            FloatArray((to - from).toInt()) { ring[((from + it) % ring.size).toInt()] } else s.samples
                        live.finalsQueued.incrementAndGet()
                        live.pending.set(null)
                        phrases.offer(Segment(padded, segStart, segEnd, noise, epoch, voicedCount, level.minVoiced,
                            voicedRms / gain.current))
                        lastSegEnd = segEnd
                        phraseStart = -1L
                    }
                }
                while (running) {
                    val n = audio.read(pcm, 0, pcm.size)
                    if (n <= 0) continue
                    if (sensitivity != level) {
                        // Switched in the picker: finish the current phrase on the old detector,
                        // then a new one (0.6 MB, instant); the speech model stays loaded.
                        vad?.flush(); drain(flushEnd = total); vad?.release()
                        level = sensitivity
                        vad = newVad(level)
                        vadBase = total
                    }
                    val f = FloatArray(n) { pcm[it] / 32768f }
                    val voiced = Voicing.isVoiced(f)
                    gain.apply(f, voiced, level.maxGain)
                    for (v in f) { ring[(total % ring.size).toInt()] = v; total++ }
                    // Listen trigger (a VRChat param, setGate): closed = hear nothing, but the
                    // model stays loaded and the mic + ring keep running, so reopening is instant.
                    val open = gateOpen && android.os.SystemClock.elapsedRealtime() >= micIgnoreUntil
                    if (open != heard) {
                        heard = open
                        if (!open) {
                            // Finish the sentence in progress (said while listening), then stop.
                            vad?.flush(); drain(flushEnd = total - n)
                            voicedRun = 0; sinceVoiced = 1000; phraseStart = -1L
                            if (shown) { shown = false; listener.onSpeechActive(false) }
                        } else {
                            // Noticed up to ~0.25 s late (OSCQuery poll): the pre-roll may reach
                            // back that far for the first word, never into the paused stretch.
                            lastSegEnd = maxOf(lastSegEnd, total - n - SAMPLE_RATE / 4)
                        }
                    }
                    if (!open) {
                        // Silence in place of the audio: keeps the VAD's count in step with the ring.
                        vad?.acceptWaveform(if (n == silence.size) silence else FloatArray(n))
                        drain()
                        continue
                    }
                    vad?.acceptWaveform(f)
                    drain()
                    // "Hearing you" (and VRChat's typing dots) only for VOICED speech: on
                    // after a few voiced windows; off when the VAD's phrase ends OR ~0.4 s
                    // after your voice stops, whichever is first (noise could hold the VAD
                    // open, so it lingered after you stopped). A bare VAD flag flipped on
                    // every breath.
                    val speech = vad?.isSpeechDetected() == true
                    voicedRun = if (!speech) 0 else if (voiced) voicedRun + 1 else voicedRun
                    sinceVoiced = if (voiced) 0 else sinceVoiced + 1
                    val talking = speech && voicedRun >= Voicing.MIN_VOICED_WINDOWS && sinceVoiced <= QUIET_WINDOWS
                    if (talking && !wasTalking) talkStartedAt = android.os.SystemClock.elapsedRealtime()
                    wasTalking = talking
                    // Paused by voice: no "Hearing you" / typing dots (only commands are heard).
                    val now = talking && !voicePaused
                    if (now != shown) { shown = now; listener.onSpeechActive(now) }
                    // Live words: once you're really speaking, hand the decoder a copy of the
                    // phrase so far every intervalMs (it adapts to the model's speed). Starts a
                    // little before the VAD noticed you (it reacts late). Not for very long
                    // phrases: the final comes soon (maxSpeechDuration) and re-reads get slow.
                    if (speech && phraseStart < 0) {
                        phraseStart = (total - n - SAMPLE_RATE * PREROLL_MS / 1000).coerceAtLeast(maxOf(0L, total - ring.size, lastSegEnd))
                        live.cutSec = 0.0
                    }
                    if (!speech && vad?.empty() != false) phraseStart = -1L
                    // Re-read from the cut (words before it are locked in), so a re-read covers
                    // ~2-3 s however long the phrase gets.
                    val cut = if (phraseStart < 0) 0L else
                        (phraseStart + (live.cutSec * SAMPLE_RATE).toLong()).coerceIn(maxOf(phraseStart, total - ring.size), total)
                    val len = total - cut
                    if (liveEnabled && !voicePaused && phraseStart >= 0 && voicedRun >= LIVE_MIN_VOICED && phrases.isEmpty() &&
                        len in (SAMPLE_RATE / 2)..(SAMPLE_RATE.toLong() * LIVE_MAX_SEC) &&
                        (total - lastPartialAt) * 1000 / SAMPLE_RATE >= live.intervalMs && live.pending.get() == null
                    ) {
                        val copy = FloatArray(len.toInt()) { ring[((cut + it) % ring.size).toInt()] }
                        live.pending.set(LiveJob(copy, phraseStart, (cut - phraseStart) / SAMPLE_RATE.toDouble(), live.finalsQueued.get()))
                        lastPartialAt = total
                    }
                }
                vad?.flush() // finish a phrase cut off by Stop
                drain(flushEnd = total)
                if (shown) listener.onSpeechActive(false)
            } catch (e: Throwable) {
                Log.e(TAG, "dictation error", e)
                listener.onLoading(false)
                listener.onError(describe(e))
            } finally {
                running = false
                captureDone.set(true)
                DictationService.stop(app)
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                runCatching { decoder?.join(15_000) } // let queued phrases finish
                runCatching { vad?.release() }
                runCatching { rec?.release() }
                if (capture === Thread.currentThread()) capture = null // fully stopped: may start again
                listener.onStopped()
            }
        }, "stt-capture")
        t.isDaemon = true
        capture = t
        t.start()
        return true
    }

    /** Ends the session; [capture] stays set until it has fully stopped (see start). */
    fun stop() {
        running = false
    }

    /** A VAD phrase: [samples] padded with a little audio before/after (soft first and last
     *  syllables), [start]/[end] = the VAD's own bounds (pauses are measured on those),
     *  noise = too little voice to decode, [epoch] = discardPending() count when queued. */
    private class Segment(
        val samples: FloatArray, val start: Long, val end: Long, val noise: Boolean, val epoch: Int,
        val voiced: Int, val minVoiced: Int, // voiced windows; the sensitivity's bar (SpeechFilter)
        val level: Float,                    // the voice's loudness at the mic, before gain
    )

    /** The unfinished phrase from [chunkSec] (s after its start) to now, to re-read for live
     *  words; seq = finals queued when copied. */
    private class LiveJob(val samples: FloatArray, val start: Long, val chunkSec: Double, val seq: Int)

    /** Shared between the capture and decode threads. */
    private class LiveState {
        val pending = java.util.concurrent.atomic.AtomicReference<LiveJob?>(null)
        val finalsQueued = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var intervalMs = LIVE_MIN_MS
        /** Where the next re-read starts (s after the phrase start): words before it are locked. */
        @Volatile var cutSec = 0.0
    }

    /** Live words on/off ("Live" vs "Phrases"); takes effect immediately, even mid-dictation. */
    @Volatile private var liveEnabled = true
    fun setLive(on: Boolean) { liveEnabled = on }

    /** Listen trigger (a VRChat avatar param via OSCQuery, decided by the caller): closed =
     *  nothing is heard while the model stays loaded, so reopening needs no model load. */
    @Volatile private var gateOpen = true
    fun setGate(open: Boolean) { gateOpen = open }

    /** Voice command words (null = commands off, e.g. while the user records a word). */
    @Volatile private var commands: Map<VoiceCommand, List<String>>? = null
    fun setCommands(words: Map<VoiceCommand, List<String>>?) { commands = words }

    /** Paused by voice: only commands are heard, nothing else goes in. */
    @Volatile private var voicePaused = false
    fun setVoicePaused(paused: Boolean) { voicePaused = paused }

    // Mic ignored until then: a command's chime must never be heard as speech.
    @Volatile private var micIgnoreUntil = 0L
    // When the wearer last started talking (capture thread): cancels a pending command.
    @Volatile private var talkStartedAt = 0L

    /** Mic sensitivity; switching mid-dictation rebuilds only the tiny voice detector. */
    @Volatile private var sensitivity = MicSensitivity.NORMAL
    fun setSensitivity(s: MicSensitivity) { sensitivity = s }

    /** Clear pressed: drop speech that's queued or still decoding, so a sentence finishing
     *  just after Clear can't put text back into the emptied chatbox. */
    @Volatile private var epoch = 0
    fun discardPending() { epoch++ }

    /** A recognised phrase: text plus its tokens with start times (s), when the model gives them. */
    private class Hyp(val text: String, val tokens: Array<String>, val times: FloatArray)

    /** Recognises one finished phrase. One implementation per model family. */
    private interface PhraseDecoder {
        fun hyp(seg: FloatArray): Hyp
        fun text(seg: FloatArray): String = hyp(seg).text
        /** Token timestamps available (live words lock in agreed words; Canary has none). */
        val hasTimes: Boolean get() = true
        fun release()
    }

    private fun phraseDecoder(ctx: Context, pack: SpeechCatalog.Pack, langCode: String): PhraseDecoder {
        // File names differ per pack (e.g. Parakeet ships int8 decoders, GigaAM fp32),
        // so resolve them from the catalog by prefix.
        fun p(prefix: String) = SpeechPacks.filePath(ctx, pack.id, pack.files.first { it.name.startsWith(prefix) }.name)
        fun pc(part: String) = SpeechPacks.filePath(ctx, pack.id, pack.files.first { it.name.contains(part) }.name)
        return when (pack.kind) {
            SpeechCatalog.Kind.NEMO_TRANSDUCER -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(encoder = p("encoder"), decoder = p("decoder"), joiner = p("joiner")),
                    tokens = p("tokens"), numThreads = DECODE_THREADS, modelType = "nemo_transducer",
                ),
                decodingMethod = "greedy_search",
            ))
            SpeechCatalog.Kind.NEMO_CTC -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    nemo = OfflineNemoEncDecCtcModelConfig(model = p("model")),
                    tokens = p("tokens"), numThreads = DECODE_THREADS,
                ),
                decodingMethod = "greedy_search",
            ))
            SpeechCatalog.Kind.TRANSDUCER -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(encoder = p("encoder"), decoder = p("decoder"), joiner = p("joiner")),
                    tokens = p("tokens"), numThreads = DECODE_THREADS, modelType = "transducer",
                ),
                decodingMethod = "greedy_search",
            ))
            SpeechCatalog.Kind.SENSE_VOICE -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    // Language set explicitly (zh / yue / ko), so it never answers in another.
                    senseVoice = OfflineSenseVoiceModelConfig(model = p("model"), language = langCode,
                        useInverseTextNormalization = true),
                    tokens = p("tokens"), numThreads = DECODE_THREADS,
                ),
                decodingMethod = "greedy_search",
            ))
            SpeechCatalog.Kind.OMNILINGUAL_CTC -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = p("model")),
                    tokens = p("tokens"), numThreads = DECODE_THREADS,
                ),
                decodingMethod = "greedy_search",
            ))
            SpeechCatalog.Kind.DOLPHIN_CTC -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    dolphin = OfflineDolphinModelConfig(model = p("model")),
                    tokens = p("tokens"), numThreads = DECODE_THREADS,
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
            ), times = false)
            SpeechCatalog.Kind.WHISPER -> offline(OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
                modelConfig = OfflineModelConfig(
                    // Whisper files are named base-encoder… / base-tokens.txt.
                    whisper = OfflineWhisperModelConfig(encoder = pc("encoder"), decoder = pc("decoder"),
                        language = langCode, task = "transcribe"),
                    tokens = pc("tokens"), numThreads = DECODE_THREADS,
                ),
                decodingMethod = "greedy_search",
            ), times = false)
            SpeechCatalog.Kind.ONLINE_TRANSDUCER -> online(OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = p("encoder"), decoder = p("decoder"), joiner = p("joiner")),
                tokens = p("tokens"), numThreads = DECODE_THREADS,
            ))
            SpeechCatalog.Kind.ONLINE_CTC -> online(OnlineModelConfig(
                zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = p("model")),
                tokens = p("tokens"), numThreads = DECODE_THREADS,
            ))
        }
    }

    /** Streaming models (Kroko, small Russian/Chinese), fed one phrase at a time. */
    private fun online(model: OnlineModelConfig): PhraseDecoder {
        val rec = OnlineRecognizer(config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0f),
            modelConfig = model,
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        ))
        val tail = FloatArray((SAMPLE_RATE * 0.8f).toInt()) // flush the model's look-ahead
        return object : PhraseDecoder {
            override fun hyp(seg: FloatArray): Hyp {
                val s = rec.createStream("")
                try {
                    s.acceptWaveform(seg, SAMPLE_RATE)
                    s.acceptWaveform(tail, SAMPLE_RATE)
                    s.inputFinished()
                    while (rec.isReady(s)) rec.decode(s)
                    val r = rec.getResult(s)
                    return Hyp(r.text.trim(), r.tokens, r.timestamps)
                } finally { s.release() }
            }
            override fun release() = rec.release()
        }
    }

    private fun offline(config: OfflineRecognizerConfig, times: Boolean = true): PhraseDecoder {
        val rec = OfflineRecognizer(config = config)
        return object : PhraseDecoder {
            override fun hyp(seg: FloatArray): Hyp {
                val s = rec.createStream()
                try {
                    s.acceptWaveform(seg, SAMPLE_RATE)
                    rec.decode(s)
                    val r = rec.getResult(s)
                    return Hyp(r.text.trim(), r.tokens, r.timestamps)
                } finally { s.release() }
            }
            override val hasTimes = times
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

    /**
     * Slow automatic gain so a quiet headset mic still triggers the voice detector. It
     * learns your level from VOICED windows only and holds still in between: the old one
     * tracked any sound, so in silence it climbed toward 8x and amplified breathing into
     * "speech" (the VAD kept firing on breaths).
     */
    private class Agc {
        private var level = 0.03f
        private var gain = 1f
        val current: Float get() = gain
        fun apply(x: FloatArray, voiced: Boolean, maxGain: Float) {
            if (voiced) {
                var sum = 0.0
                for (v in x) sum += v * v
                val rms = sqrt(sum / x.size).toFloat()
                level = level * 0.95f + rms * 0.05f
                val target = (0.063f / level).coerceIn(1f, maxGain)
                gain += (target - gain) * 0.1f
            }
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
