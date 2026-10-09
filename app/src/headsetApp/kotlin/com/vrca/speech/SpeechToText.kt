package com.vrca.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Headset-flavor OFFLINE speech-to-text (Vosk) — the engine behind voice-to-text
 * dictation into the Manual Send chatbox field. On-device: no network/API cost after
 * a one-time model download, and the recognizer + model live in RAM ONLY while
 * listening (released on [stop]), so idle cost is zero. Works on Quest, which has no
 * Google SpeechRecognizer.
 *
 * The 144-char chatbox limit is NOT a concern here: the UI routes the growing
 * transcript through Manual Send's Live + Scroll path, which windows the newest lines
 * within the budget and scrolls older ones off — so a long dictation never gets cut.
 *
 * Signature MUST match the publicApp/adminApp stubs (SUPPORTED=false there).
 */
object SpeechToText {
    const val SUPPORTED = true

    private const val TAG = "SpeechToText"
    private const val SAMPLE_RATE = 16000
    private const val MODEL_DIR = "vosk-model"

    /** Vosk small English model (~40 MB), fetched on demand — never bundled. */
    private const val MODEL_URL =
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

    interface Listener {
        /** The current, still-growing utterance (replace the live tail with this). */
        fun onPartial(text: String)
        /** A finished utterance (append this + a space, start a fresh tail). */
        fun onFinal(text: String)
        fun onError(message: String)
    }

    @Volatile private var running = false
    @Volatile private var worker: Thread? = null

    fun isListening(): Boolean = running

    private fun modelDir(ctx: Context): File = File(ctx.filesDir, MODEL_DIR)

    fun modelReady(ctx: Context): Boolean {
        val d = modelDir(ctx)
        return d.isDirectory && File(d, "am").exists() && File(d, "conf").exists()
    }

    /** Download + unzip the model on a background thread. [onProgress] is 0..100. */
    fun downloadModel(
        ctx: Context,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String?) -> Unit,
    ) {
        Thread({
            try {
                if (modelReady(ctx)) { onProgress(100); onDone(true, null); return@Thread }
                val tmpZip = File(ctx.cacheDir, "vosk-model.zip")
                val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "VRC-A/1.0")
                }
                if (conn.responseCode !in 200..299) {
                    onDone(false, "Download failed (HTTP ${conn.responseCode})"); return@Thread
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(tmpZip).use { out ->
                        val buf = ByteArray(65_536)
                        var read = 0L
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n == -1) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 95) / total).toInt().coerceIn(0, 95)
                                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                            }
                        }
                    }
                }
                onProgress(96)
                val dir = modelDir(ctx)
                deleteTree(dir); dir.mkdirs()
                unzipFlattened(tmpZip, dir)
                tmpZip.delete()
                if (modelReady(ctx)) { onProgress(100); onDone(true, null) }
                else { deleteTree(dir); onDone(false, "Model files missing after unzip") }
            } catch (e: Throwable) {
                Log.e(TAG, "model download failed", e)
                onDone(false, describe(e))
            }
        }, "vosk-download").start()
    }

    /**
     * Start streaming recognition. The caller MUST have RECORD_AUDIO granted (the UI
     * checks). Returns false if it can't start (no model / mic unavailable). Partial +
     * final results are delivered on the worker thread — the UI must hop to the main
     * thread itself.
     */
    @SuppressLint("MissingPermission")
    fun start(ctx: Context, listener: Listener): Boolean {
        if (running) return true
        if (!modelReady(ctx)) { listener.onError("Voice model not downloaded yet."); return false }
        running = true
        val t = Thread({
            var model: Model? = null
            var rec: Recognizer? = null
            var audio: AudioRecord? = null
            try {
                model = Model(modelDir(ctx).absolutePath)
                rec = Recognizer(model, SAMPLE_RATE.toFloat())
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(SAMPLE_RATE) // ~1s floor
                audio = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf
                )
                if (audio.state != AudioRecord.STATE_INITIALIZED) {
                    listener.onError("Microphone unavailable."); return@Thread
                }
                audio.startRecording()
                val buf = ShortArray(minBuf / 2)
                while (running) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    if (rec.acceptWaveForm(buf, n)) {
                        val text = textOf(rec.result, "text")
                        if (text.isNotEmpty()) listener.onFinal(text)
                    } else {
                        val partial = textOf(rec.partialResult, "partial")
                        if (partial.isNotEmpty()) listener.onPartial(partial)
                    }
                }
                val tail = textOf(rec.finalResult, "text")
                if (tail.isNotEmpty()) listener.onFinal(tail)
            } catch (e: Throwable) {
                Log.e(TAG, "recognition error", e)
                listener.onError(describe(e))
            } finally {
                running = false
                runCatching { audio?.stop() }
                runCatching { audio?.release() }
                runCatching { rec?.close() }
                runCatching { model?.close() }
            }
        }, "vosk-recognize")
        t.isDaemon = true
        worker = t
        t.start()
        return true
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }

    private fun textOf(json: String?, key: String): String =
        try { JSONObject(json ?: "{}").optString(key, "").trim() } catch (_: Exception) { "" }

    /**
     * Build a human-readable error that names the exception TYPE and walks the cause
     * chain. A bare `e.message` is ambiguous for native-init failures — an
     * ExceptionInInitializerError has a null message and a NoClassDefFoundError's
     * message is just the class name, so the device only ever showed "org.vosk.LibVosk"
     * with no hint of the real UnsatisfiedLinkError underneath. This surfaces the chain.
     */
    private fun describe(t: Throwable): String {
        val sb = StringBuilder()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 5) {
            if (depth > 0) sb.append(" <- ")
            sb.append(cur.javaClass.simpleName)
            cur.message?.takeIf { it.isNotBlank() }?.let { sb.append(": ").append(it) }
            cur = cur.cause
            depth++
        }
        return sb.toString().ifBlank { "speech error" }
    }

    private fun unzipFlattened(zip: File, dest: File) {
        ZipInputStream(zip.inputStream()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                var name = e.name.replace('\\', '/')
                val slash = name.indexOf('/')
                if (slash >= 0) name = name.substring(slash + 1) // drop the top-level model dir
                if (name.isNotEmpty() && !name.contains("..")) {
                    val f = File(dest, name)
                    if (e.isDirectory) {
                        f.mkdirs()
                    } else {
                        f.parentFile?.mkdirs()
                        FileOutputStream(f).use { zis.copyTo(it) }
                    }
                }
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
    }

    private fun deleteTree(f: File?) {
        if (f == null || !f.exists()) return
        f.listFiles()?.forEach { deleteTree(it) }
        f.delete()
    }
}
