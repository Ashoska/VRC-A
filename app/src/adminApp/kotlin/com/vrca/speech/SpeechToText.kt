package com.vrca.speech

import android.content.Context

/**
 * Admin-flavor stub. Offline speech-to-text (voice-to-text dictation) is a HEADSET
 * feature backed by Vosk (`headsetAppImplementation`), not on the admin classpath —
 * so SUPPORTED is false and the mic affordance is hidden. Signature MUST match the
 * headsetApp SpeechToText.
 */
object SpeechToText {
    const val SUPPORTED = false

    interface Listener {
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    fun isListening(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun modelReady(ctx: Context): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun downloadModel(ctx: Context, onProgress: (Int) -> Unit, onDone: (Boolean, String?) -> Unit) {
        onDone(false, "Voice-to-text isn't available on this build.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun start(ctx: Context, listener: Listener): Boolean {
        listener.onError("Voice-to-text isn't available on this build.")
        return false
    }

    fun stop() {}
}
