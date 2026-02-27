package com.scrapw.chatbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class NowPlayingSnapshot(
    val listenerConnected: Boolean = false,
    val activePackage: String = "",
    val detected: Boolean = false,
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0L,

    // Raw position snapshot from PlaybackState
    val positionMs: Long = 0L,

    // elapsedRealtime at the moment positionMs was measured
    val positionUpdateTimeMs: Long = 0L,
    val playbackSpeed: Float = 1f,

    // This may be wrong during skips/seek on some players.
    // We may override it in NowPlayingState.update() based on motion.
    val isPlaying: Boolean = false
)

object NowPlayingState {
    private val _state = MutableStateFlow(NowPlayingSnapshot())
    val state: StateFlow<NowPlayingSnapshot> = _state.asStateFlow()

    // Heuristic knobs (tuned for your 2s refresh + common media-session jitter)
    private const val MOVING_POS_DELTA_MS = 250L     // position must advance by at least this to be "moving"
    private const val STALLED_POS_DELTA_MS = 60L     // treat <= this as "not moving"
    private const val STALLED_TIME_MS = 1400L        // if not moving for >= this, call it paused

    fun update(snapshot: NowPlayingSnapshot) {
        val prev = _state.value

        // Reset motion history if app changed (prevents false "paused" on app switch)
        val samePkg = prev.activePackage == snapshot.activePackage && snapshot.activePackage.isNotBlank()

        val inferredIsPlaying = inferIsPlayingFromMotion(
            prev = prev,
            cur = snapshot,
            samePkg = samePkg
        )

        _state.value = snapshot.copy(isPlaying = inferredIsPlaying)
    }

    fun setConnected(connected: Boolean) {
        _state.value = _state.value.copy(listenerConnected = connected)
    }

    /**
     * When a media notification/session disappears, DON'T blank the UI.
     * Keep the last known title/artist and simply mark it as paused.
     * This prevents the UI/OSC text from randomly disappearing when players hide notifications.
     */
    fun pauseIfActivePackage(pkg: String) {
        val cur = _state.value
        if (cur.activePackage == pkg && (cur.title.isNotBlank() || cur.artist.isNotBlank())) {
            _state.value = cur.copy(
                detected = true,
                // Keep title/artist/duration/position as the last known state
                playbackSpeed = 0f,
                isPlaying = false
            )
        } else if (cur.activePackage == pkg) {
            // If we have nothing meaningful, clear like before.
            clearIfActivePackage(pkg)
        }
    }

    /**
     * Hard clear (used when you really want to remove the "Now Playing" info).
     */
    fun clearIfActivePackage(pkg: String) {
        val cur = _state.value
        if (cur.activePackage == pkg) {
            _state.value = cur.copy(
                detected = false,
                title = "",
                artist = "",
                durationMs = 0L,
                positionMs = 0L,
                positionUpdateTimeMs = 0L,
                playbackSpeed = 1f,
                isPlaying = false
            )
        }
    }

    private fun inferIsPlayingFromMotion(
        prev: NowPlayingSnapshot,
        cur: NowPlayingSnapshot,
        samePkg: Boolean
    ): Boolean {
        // If nothing detected, weâ€™re not playing.
        if (!cur.detected) return false

        // If we don't have timing info, fall back to whatever the service reported.
        if (cur.positionUpdateTimeMs <= 0L) return cur.isPlaying

        // If we can't compare to a previous sample (first sample or app changed), fall back.
        if (!samePkg || prev.positionUpdateTimeMs <= 0L) return cur.isPlaying

        val dt = cur.positionUpdateTimeMs - prev.positionUpdateTimeMs
        if (dt <= 0L) return cur.isPlaying

        val dp = cur.positionMs - prev.positionMs
        val adp = abs(dp)

        // If speed is 0, assume paused (some services report this reliably)
        if (cur.playbackSpeed == 0f) return false

        // If position moved enough, it's playing.
        if (adp >= MOVING_POS_DELTA_MS) return true

        // If position is basically unchanged for long enough, call it paused.
        if (adp <= STALLED_POS_DELTA_MS && dt >= STALLED_TIME_MS) return false

        // Otherwise don't fight the service too hard.
        return cur.isPlaying
    }
}
