package com.vrca.speech

import android.content.Context

/**
 * publicApp stub. Offline voice-to-text is a HEADSET feature (the sherpa-onnx engine is a
 * `headsetAppImplementation` dependency, so its native libs aren't in this APK) —
 * SUPPORTED is false and the Manual Send mic affordance is hidden. Signature MUST match
 * the headsetApp SpeechToText. (Pack catalog + downloads are shared code in main.)
 */
object SpeechToText {
    const val SUPPORTED = false

    interface Listener {
        fun onPartial(text: String, pauseBeforeSec: Float) {}
        fun onFinal(text: String, pauseBeforeSec: Float, decodeMs: Long)
        fun onError(message: String)
        fun onSpeechActive(active: Boolean) {}
        fun onLoading(loading: Boolean) {}
        /** Listening ended for any reason (Stop, the notification's Stop, an error). */
        fun onStopped() {}
        fun onCommand(command: VoiceCommand) {}
        fun onCommandRejected(command: VoiceCommand, miss: CommandMiss, heard: String) {}
    }

    fun isListening(): Boolean = false
    fun setLive(on: Boolean) {}
    fun setSensitivity(s: MicSensitivity) {}
    fun discardPending() {}
    fun setGate(open: Boolean) {}
    fun setCommands(words: Map<VoiceCommand, List<String>>?) {}
    fun setVoicePaused(paused: Boolean) {}

    @Suppress("UNUSED_PARAMETER")
    fun start(ctx: Context, langCode: String, listener: Listener): Boolean {
        listener.onError("Voice-to-text isn't available on this build.")
        return false
    }

    fun stop() {}
}
