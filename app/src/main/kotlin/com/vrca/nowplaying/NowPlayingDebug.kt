package com.vrca.nowplaying

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * TEMPORARY diagnostic holder for YouTube ad-detection research.
 *
 * The hypothesis: a pre-roll/mid-roll YouTube ad can be told apart from a genuine
 * short video — instantly, without a network lookup — because YouTube strips
 * `ACTION_SEEK_TO` from the MediaSession `PlaybackState` during ads (you can't
 * scrub an ad), and/or exposes a "skip ad" custom action, and/or reports a
 * `position > duration` / shifting `mediaId` while the title stays frozen.
 *
 * This object records the RAW per-poll signals so they can be read back on-device
 * (Settings → Debug) AFTER playing a video that triggers a pre-roll, then a real
 * Short — to see which signals actually separate the two. It writes nothing to
 * Firestore and is not wired into the chatbox. Remove once the detector is built.
 */
object NowPlayingDebug {
    private const val MAX_LINES = 80

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    // Dedup consecutive identical signal payloads (ignoring the timestamp) so the
    // 500ms YouTube poll doesn't flood the log with the same line.
    @Volatile private var lastSignal: String = ""

    /** Append a raw-signal line. [signal] is the dedup key; [stamp] is prefixed. */
    @Synchronized
    fun record(stamp: String, signal: String) {
        if (signal == lastSignal) return
        lastSignal = signal
        val next = _lines.value.toMutableList()
        next.add("$stamp  $signal")
        while (next.size > MAX_LINES) next.removeAt(0)
        _lines.value = next
    }

    @Synchronized
    fun clear() {
        lastSignal = ""
        _lines.value = emptyList()
    }
}
