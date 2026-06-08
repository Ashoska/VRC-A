package com.vrca.nowplaying

import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Lightweight, TEMPORARY debug holder for the YouTube / YouTube Music ad-detection
 * signals. Surfaced in Settings → Debug so the seekability + buffering-gate state
 * machine can be validated on-device (does a song switch report BUFFERING? does an
 * ad report PLAYING+seek=N?). Safe to delete once detection is confirmed stable.
 */
object NowPlayingDebug {
    private val _trace = MutableStateFlow("(no YouTube signal yet)")
    val trace: StateFlow<String> = _trace.asStateFlow()

    fun record(
        pkg: String,
        state: Int,
        seekable: Boolean,
        durationMs: Long,
        streak: Int,
        adDetected: Boolean,
        windowActive: Boolean,
        isAdWindow: Boolean
    ) {
        val app = when (pkg) {
            "com.google.android.apps.youtube.music" -> "YT Music"
            "com.google.android.youtube" -> "YouTube"
            else -> pkg
        }
        _trace.value = buildString {
            append(app)
            append("  state=").append(stateName(state))
            append("  seek=").append(if (seekable) "Y" else "N")
            append("  dur=").append(durationMs / 1000).append("s")
            append("  streak=").append(streak)
            append("\nad=").append(adDetected)
            append("  window=").append(windowActive)
            append("  adWindow=").append(isAdWindow)
        }
    }

    private fun stateName(s: Int): String = when (s) {
        PlaybackState.STATE_NONE -> "NONE"
        PlaybackState.STATE_STOPPED -> "STOPPED"
        PlaybackState.STATE_PAUSED -> "PAUSED"
        PlaybackState.STATE_PLAYING -> "PLAYING"
        PlaybackState.STATE_BUFFERING -> "BUFFERING"
        PlaybackState.STATE_CONNECTING -> "CONNECTING"
        PlaybackState.STATE_ERROR -> "ERROR"
        -99 -> "n/a"
        else -> "S$s"
    }
}
