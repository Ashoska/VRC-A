package com.vrca.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin

/**
 * Short chimes confirming a voice command, since you can't see the app in VR and the
 * chatbox mustn't show it: falling = paused, rising = resumed, a double blip = cleared.
 * Generated tones (no assets), played on the game/media volume next to VRChat's audio (the
 * system-sounds volume can be muted on its own). The engine ignores the mic meanwhile, so a
 * chime is never heard as speech.
 */
object CommandSounds {
    private const val RATE = 22_050
    private const val AMP = 0.22

    /** How long the mic is ignored after a command fires (covers the chime + echo, also on
     *  an output with extra delay such as Bluetooth headphones). */
    const val MIC_IGNORE_MS = 900L

    fun play(cmd: VoiceCommand) {
        val pcm = when (cmd) {
            VoiceCommand.PAUSE -> tones(784, 523)
            VoiceCommand.RESUME -> tones(523, 784)
            VoiceCommand.CLEAR -> tones(988, 988)
        }
        Thread({
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                try {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    // Release only once every frame has been played out. A fixed sleep (the
                    // sound's length + 0.1 s) cut the chime off before it was heard on outputs
                    // with more delay, e.g. Bluetooth (user-reported: commands worked, no sound).
                    val deadline = SystemClock.elapsedRealtime() + 3_000
                    while (track.playbackHeadPosition < pcm.size && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(20)
                    }
                    Thread.sleep(300) // what's already in the output path still has to drain
                } finally { track.release() }
            }.onFailure { Log.w("CommandSounds", "chime failed", it) }
        }, "command-sound").apply { isDaemon = true }.start()
    }

    /** 90 ms notes with 10 ms fades (no clicks) and 30 ms gaps. */
    private fun tones(vararg freqs: Int): ShortArray {
        val note = RATE * 90 / 1000
        val gap = RATE * 30 / 1000
        val fade = RATE * 10 / 1000f
        val out = ShortArray(freqs.size * (note + gap))
        var o = 0
        for (f in freqs) {
            for (i in 0 until note) {
                val env = minOf(1f, i / fade, (note - i) / fade)
                out[o + i] = (sin(2 * PI * f * i / RATE) * env * AMP * Short.MAX_VALUE).toInt().toShort()
            }
            o += note + gap
        }
        return out
    }
}
